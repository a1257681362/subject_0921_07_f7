package com.evops.booking;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.booking.conflict.BookingConflictStrategy;
import com.evops.booking.conflict.ConflictInfo;
import com.evops.booking.conflict.LegacyBookingConflictStrategy;
import com.evops.booking.conflict.NewBookingConflictStrategy;
import com.evops.booking.dto.AppointmentBookRequest;
import com.evops.booking.dto.AppointmentCancelRequest;
import com.evops.booking.dto.AppointmentConfirmRequest;
import com.evops.booking.entity.MachineAppointment;
import com.evops.booking.entity.MachineAppointmentLog;
import com.evops.booking.mapper.MachineAppointmentLogMapper;
import com.evops.booking.mapper.MachineAppointmentMapper;
import com.evops.booking.vo.AppointmentDetail;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.machine.MachineStatus;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.request.RequestRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 农机预约排班主链路。
 *
 * <p>防撞：新增预约先按当前口径查重——新旧策略都拦严格时段重叠；新口径另拦"相邻不足缓冲分钟"，
 * 首尾相接（端点相等）豁免。同机并发由 JVM 分段锁串行，检查与落库同事务。
 *
 * <p>临时锁：预约提交先置 HELD，只保留 lockTtl 分钟（默认 35）；确认转 CONFIRMED 长期占用，
 * 超时未确认由定时扫描/惰性检查自动 EXPIRED 释放；取消立即 CANCELLED 释放、时段可再约。
 *
 * <p>预案：mode=LEGACY 时整体退回旧口径（仅严格重叠），不依赖缓冲/临时锁相关新逻辑做拦截，
 * 出异常一键切回即可恢复旧手工台账行为。
 */
@Service
public class AppointmentService {

    private static final Logger log = LoggerFactory.getLogger(AppointmentService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final int LOCK_STRIPES = 64;

    private final MachineAppointmentMapper appointmentMapper;
    private final MachineAppointmentLogMapper logMapper;
    private final MachineMapper machineMapper;
    private final RequestRecordService requestRecordService;
    private final BookingConfigService configService;
    private final NewBookingConflictStrategy newStrategy;
    private final LegacyBookingConflictStrategy legacyStrategy;
    private final AppointmentService self;
    private final ReentrantLock[] bookLocks = new ReentrantLock[LOCK_STRIPES];

    public AppointmentService(MachineAppointmentMapper appointmentMapper,
                              MachineAppointmentLogMapper logMapper,
                              MachineMapper machineMapper,
                              RequestRecordService requestRecordService,
                              BookingConfigService configService,
                              NewBookingConflictStrategy newStrategy,
                              LegacyBookingConflictStrategy legacyStrategy,
                              @Lazy AppointmentService self) {
        this.appointmentMapper = appointmentMapper;
        this.logMapper = logMapper;
        this.machineMapper = machineMapper;
        this.requestRecordService = requestRecordService;
        this.configService = configService;
        this.newStrategy = newStrategy;
        this.legacyStrategy = legacyStrategy;
        this.self = self;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            bookLocks[i] = new ReentrantLock();
        }
    }

    /**
     * 新增预约：请求号登记 → 校验农机/时段 → 释放该机已超时临时锁 → 防撞检查 → 落 HELD 临时锁。
     * HELD 占位于确认前仅保留 35 分钟（当时口径快照），逾期自动放掉。
     */
    @Transactional(rollbackFor = Exception.class)
    public MachineAppointment book(AppointmentBookRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.APPOINTMENT_BOOK,
                req.getMachineId() + "@" + req.getStartTime());

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = req.getStartTime();
        LocalDateTime end = req.getEndTime();
        if (!end.isAfter(start)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }
        if (!end.isAfter(now)) {
            throw new BusinessException("预约结束时间已过，不能预约过去的时段");
        }
        Machine machine = machineMapper.selectById(req.getMachineId());
        if (machine == null) {
            throw new BusinessException("农机不存在: " + req.getMachineId());
        }
        if (MachineStatus.DISABLED.equals(machine.getStatus())) {
            throw new BusinessException("农机已停用，不能预约");
        }

        int bufferMinutes = configService.getBufferMinutes();
        int lockTtlMinutes = configService.getLockTtlMinutes();
        String mode = configService.getMode();

        ReentrantLock lock = lockFor(req.getMachineId());
        lock.lock();
        try {
            // 先把该机已超时的 HELD 惰性释放，腾出时段
            expireHeldLocks(req.getMachineId(), now, null);

            List<MachineAppointment> occupied = listOccupied(req.getMachineId());
            BookingConflictStrategy strategy = BookingMode.LEGACY.equals(mode) ? legacyStrategy : newStrategy;
            ConflictInfo conflict = strategy.check(occupied, start, end, bufferMinutes, now);
            if (conflict != null) {
                if (BookingMode.PARALLEL.equals(mode)
                        && ConflictInfo.BUFFER.equals(conflict.getType())) {
                    // 并跑观察：缓冲拦截是新口径独有，旧口径此时会放行——只记账差异，仍按新口径拦
                    log.warn("并跑口径分歧：新口径按缓冲拦截，旧口径将放行（{}）", conflict.getMessage());
                }
                throw new BusinessException("预约冲突：" + conflict.getMessage());
            }

            MachineAppointment appt = new MachineAppointment();
            appt.setAppointmentNo(nextAppointmentNo(machine.getId()));
            appt.setMachineId(machine.getId());
            appt.setPlotName(req.getPlotName());
            appt.setOperatorCode(req.getOperatorCode().trim());
            appt.setStartTime(start);
            appt.setEndTime(end);
            appt.setStatus(AppointmentStatus.HELD);
            appt.setLockDeadline(now.plusMinutes(lockTtlMinutes));
            appt.setBufferMinutes(bufferMinutes);
            appt.setLockTtlMinutes(lockTtlMinutes);
            appt.setRequestNo(req.getRequestNo());
            appt.setRemark(req.getRemark());
            try {
                appointmentMapper.insert(appt);
            } catch (org.springframework.dao.DuplicateKeyException e) {
                // 跨实例/极端并发下预约号撞车：友好报错让用户重试，请求号随事务回退
                throw new BusinessException("预约提交冲突，请重试");
            }

            writeLog(appt, null, AppointmentStatus.HELD, req.getOperatorCode(), req.getRequestNo(),
                    "预约临时锁定，需在 " + lockTtlMinutes + " 分钟内确认（口径 " + mode
                            + "，缓冲 " + bufferMinutes + " 分钟）");
            log.info("预约 {} 已临时锁定：农机 {} {} ~ {}，口径 {}", appt.getAppointmentNo(),
                    machine.getId(), start, end, mode);
            return appt;
        } finally {
            lock.unlock();
        }
    }

    /** 确认预约：HELD→CONFIRMED；超时未确认的临时锁直接判过期并拒绝。凭版本乐观锁。 */
    @Transactional(rollbackFor = Exception.class)
    public MachineAppointment confirm(Long id, AppointmentConfirmRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.APPOINTMENT_CONFIRM, String.valueOf(id));

        MachineAppointment appt = requireAppointment(id);
        if (!req.getVersion().equals(appt.getVersion())) {
            throw new BusinessException("预约已被他人变更（当前版本 " + appt.getVersion() + "），请刷新后重试");
        }
        if (AppointmentStatus.CANCELLED.equals(appt.getStatus())) {
            throw new BusinessException("预约已取消，不能确认");
        }
        if (AppointmentStatus.CONFIRMED.equals(appt.getStatus())) {
            throw new BusinessException("预约已确认，请勿重复确认");
        }
        if (AppointmentStatus.EXPIRED.equals(appt.getStatus())) {
            throw new BusinessException("临时锁已过期，预约已释放，请重新预约");
        }
        // HELD：到达/越过锁定截止即自动释放。独立事务先提交过期，再拒绝本次确认，
        // 避免外层事务因业务异常回滚把"过期释放"一并撤回。
        if (appt.getLockDeadline() != null && !LocalDateTime.now().isBefore(appt.getLockDeadline())) {
            self.expireIfHeld(id);
            throw new BusinessException("临时锁已超过 35 分钟未确认，预约自动释放，请重新预约");
        }

        appt.setStatus(AppointmentStatus.CONFIRMED);
        appt.setLockDeadline(null);
        if (appointmentMapper.updateById(appt) == 0) {
            throw new BusinessException("预约已被他人变更，请刷新后重试");
        }
        writeLog(appt, AppointmentStatus.HELD, AppointmentStatus.CONFIRMED, req.getOperator(),
                req.getRequestNo(), req.getRemark() != null && !req.getRemark().trim().isEmpty()
                        ? req.getRemark() : "预约确认，时段长期占用");
        return appt;
    }

    /** 取消预约：HELD/CONFIRMED→CANCELLED，立即释放时段、可再约，历史保留。凭版本乐观锁。 */
    @Transactional(rollbackFor = Exception.class)
    public MachineAppointment cancel(Long id, AppointmentCancelRequest req) {
        requestRecordService.register(req.getRequestNo(), BizType.APPOINTMENT_CANCEL, String.valueOf(id));

        MachineAppointment appt = requireAppointment(id);
        if (!req.getVersion().equals(appt.getVersion())) {
            throw new BusinessException("预约已被他人变更（当前版本 " + appt.getVersion() + "），请刷新后重试");
        }
        String from = appt.getStatus();
        if (AppointmentStatus.CANCELLED.equals(from)) {
            throw new BusinessException("预约已取消，请勿重复操作");
        }
        if (AppointmentStatus.EXPIRED.equals(from)) {
            throw new BusinessException("预约已过期释放，无需取消");
        }
        String reason = req.getCancelReason() != null && !req.getCancelReason().trim().isEmpty()
                ? req.getCancelReason().trim() : "机手/调度取消";
        appt.setStatus(AppointmentStatus.CANCELLED);
        appt.setLockDeadline(null);
        appt.setCancelReason(reason);
        if (appointmentMapper.updateById(appt) == 0) {
            throw new BusinessException("预约已被他人变更，请刷新后重试");
        }
        writeLog(appt, from, AppointmentStatus.CANCELLED, req.getOperator(), req.getRequestNo(),
                "取消并立即释放时段：" + reason);
        log.info("预约 {} 取消，时段已释放可再约", appt.getAppointmentNo());
        return appt;
    }

    /**
     * 定时扫描：把所有已到点的 HELD 临时锁逐笔独立事务翻成 EXPIRED。
     * 一笔失败不影响其余，下一轮继续；与确认并发时由版本乐观锁裁决，只有一方成功。
     *
     * @return 本轮实际释放笔数
     */
    public int sweepExpiredLocks() {
        LocalDateTime now = LocalDateTime.now();
        List<Long> dueIds = appointmentMapper.selectList(new LambdaQueryWrapper<MachineAppointment>()
                        .select(MachineAppointment::getId)
                        .eq(MachineAppointment::getStatus, AppointmentStatus.HELD)
                        .le(MachineAppointment::getLockDeadline, now))
                .stream().map(MachineAppointment::getId).collect(java.util.stream.Collectors.toList());
        int released = 0;
        for (Long id : dueIds) {
            if (self.expireIfHeld(id)) {
                released++;
            }
        }
        return released;
    }

    /** 单笔过期翻转（独立事务，幂等）：仅当仍是 HELD 才成功。定时与惰性共用。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW, rollbackFor = Exception.class)
    public boolean expireIfHeld(Long id) {
        MachineAppointment appt = appointmentMapper.selectById(id);
        if (appt == null || !AppointmentStatus.HELD.equals(appt.getStatus())) {
            return false;
        }
        if (appt.getLockDeadline() != null && LocalDateTime.now().isBefore(appt.getLockDeadline())) {
            return false;
        }
        flipToExpired(appt, "SYSTEM", "AUTO-EXPIRE-" + id);
        return true;
    }

    public AppointmentDetail detail(Long id) {
        MachineAppointment appt = requireAppointment(id);
        AppointmentDetail detail = new AppointmentDetail();
        detail.setAppointment(appt);
        detail.setLogs(logMapper.selectList(new LambdaQueryWrapper<MachineAppointmentLog>()
                .eq(MachineAppointmentLog::getAppointmentId, id)
                .orderByAsc(MachineAppointmentLog::getId)));
        return detail;
    }

    /** 列表：可按农机/状态过滤，开始时间升序（同机日排班视图）。 */
    public List<MachineAppointment> list(Long machineId, String status, int limit) {
        return appointmentMapper.selectList(new LambdaQueryWrapper<MachineAppointment>()
                .eq(machineId != null, MachineAppointment::getMachineId, machineId)
                .eq(status != null && !status.isEmpty(), MachineAppointment::getStatus, status)
                .orderByAsc(MachineAppointment::getStartTime)
                .orderByAsc(MachineAppointment::getId)
                .last("limit " + Math.max(1, Math.min(limit, 200))));
    }

    // ---- internals ----

    /** 当前仍占位的预约：HELD（未过期）与 CONFIRMED。调用方应已先做惰性过期。 */
    private List<MachineAppointment> listOccupied(Long machineId) {
        return appointmentMapper.selectList(new LambdaQueryWrapper<MachineAppointment>()
                .eq(MachineAppointment::getMachineId, machineId)
                .in(MachineAppointment::getStatus, AppointmentStatus.HELD, AppointmentStatus.CONFIRMED)
                .orderByAsc(MachineAppointment::getStartTime));
    }

    /** 在当前事务内把某台农机已到点的 HELD 锁翻成 EXPIRED（供预约前惰性释放）。 */
    private void expireHeldLocks(Long machineId, LocalDateTime now, String operator) {
        List<MachineAppointment> due = appointmentMapper.selectList(new LambdaQueryWrapper<MachineAppointment>()
                .eq(MachineAppointment::getMachineId, machineId)
                .eq(MachineAppointment::getStatus, AppointmentStatus.HELD)
                .le(MachineAppointment::getLockDeadline, now));
        for (MachineAppointment appt : due) {
            flipToExpired(appt, operator != null ? operator : "SYSTEM",
                    "AUTO-EXPIRE-" + appt.getId());
        }
    }

    /** 条件翻转 HELD→EXPIRED：乐观锁保证与确认/取消并发只有一方成功；成功才写日志。 */
    private void flipToExpired(MachineAppointment appt, String operator, String requestNo) {
        String from = appt.getStatus();
        appt.setStatus(AppointmentStatus.EXPIRED);
        appt.setLockDeadline(null);
        if (appointmentMapper.updateById(appt) == 0) {
            // 已被确认/取消抢先，放弃本次翻转
            return;
        }
        writeLog(appt, from, AppointmentStatus.EXPIRED, operator, requestNo, "临时锁超时未确认，自动释放时段");
        log.info("预约 {} 临时锁过期，时段自动释放", appt.getAppointmentNo());
    }

    private MachineAppointment requireAppointment(Long id) {
        MachineAppointment appt = appointmentMapper.selectById(id);
        if (appt == null) {
            throw new BusinessException("预约不存在: " + id);
        }
        return appt;
    }

    private void writeLog(MachineAppointment appt, String from, String to,
                          String operator, String requestNo, String remark) {
        MachineAppointmentLog entry = new MachineAppointmentLog();
        entry.setAppointmentId(appt.getId());
        entry.setAppointmentNo(appt.getAppointmentNo());
        entry.setFromStatus(from);
        entry.setToStatus(to);
        entry.setOperator(operator);
        entry.setRequestNo(requestNo);
        entry.setRemark(remark);
        logMapper.insert(entry);
    }

    private ReentrantLock lockFor(Long machineId) {
        return bookLocks[Math.floorMod(machineId.hashCode(), LOCK_STRIPES)];
    }

    /**
     * 预约号：AP + 日期 + 农机ID + 该机当日序号（含已取消/过期，序号不复用）。
     * 同机取号在分段锁内串行、跨机前缀不同，单机内不会撞号；唯一约束兜底跨实例并发。
     */
    private String nextAppointmentNo(Long machineId) {
        String day = LocalDateTime.now().format(DATE_FMT);
        String prefix = "AP" + day + "-" + machineId + "-";
        long count = appointmentMapper.selectCount(new LambdaQueryWrapper<MachineAppointment>()
                .likeRight(MachineAppointment::getAppointmentNo, prefix));
        return prefix + String.format("%03d", count + 1);
    }
}
