package com.evops.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.machine.MachineStatus;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.request.RequestRecordService;
import com.evops.schedule.dto.ScheduleCancelRequest;
import com.evops.schedule.dto.ScheduleConfirmRequest;
import com.evops.schedule.dto.ScheduleCreateRequest;
import com.evops.schedule.entity.MachineSchedule;
import com.evops.schedule.mapper.MachineScheduleMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.List;

/**
 * 排班落库事务操作。所有写方法单事务：请求号台账与预约状态一起提交或整体回退。
 * 本类只负责事务内逻辑，不做加锁；按农机的 JVM 分段锁由 {@link MachineScheduleService}
 * 在事务外层包裹，保证后一笔能读到前一笔已提交的占位。
 */
@Component
public class MachineScheduleTxOps {

    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private final MachineScheduleMapper scheduleMapper;
    private final MachineMapper machineMapper;
    private final RequestRecordService requestRecordService;
    private final ScheduleProperties properties;

    public MachineScheduleTxOps(MachineScheduleMapper scheduleMapper,
                                MachineMapper machineMapper,
                                RequestRecordService requestRecordService,
                                ScheduleProperties properties) {
        this.scheduleMapper = scheduleMapper;
        this.machineMapper = machineMapper;
        this.requestRecordService = requestRecordService;
        this.properties = properties;
    }

    /**
     * 新逻辑（NEW）：登记请求号 → 校验农机/时间 → 先释放该机过期临时锁 →
     * 查现有占位逐条判重叠与 18 分钟缓冲 → 落 PENDING（lock_until = now+35 分钟）。
     */
    @Transactional(rollbackFor = Exception.class)
    public MachineSchedule createNew(ScheduleCreateRequest req, LocalDateTime now) {
        requestRecordService.register(req.getRequestNo(), BizType.MACHINE_SCHEDULE_CREATE, req.getScheduleNo());
        Machine machine = requireActiveMachine(req.getMachineId());
        LocalDateTime start = parseTime(req.getStartTime(), "开始时间");
        LocalDateTime end = parseTime(req.getEndTime(), "结束时间");
        if (!end.isAfter(start)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }

        // 先把该机已过期的待确认占位放掉，过期时段可被新预约占用
        expireForMachine(machine.getId(), now);

        List<MachineSchedule> occupied = scheduleMapper.selectList(new LambdaQueryWrapper<MachineSchedule>()
                .eq(MachineSchedule::getMachineId, machine.getId())
                .in(MachineSchedule::getStatus,
                        ScheduleConstants.STATUS_PENDING, ScheduleConstants.STATUS_CONFIRMED)
                .orderByAsc(MachineSchedule::getStartTime));
        assertNoClash(req.getScheduleNo(), start, end, occupied);

        MachineSchedule schedule = baseSchedule(req, machine, start, end);
        schedule.setStatus(ScheduleConstants.STATUS_PENDING);
        schedule.setSource(ScheduleConstants.SOURCE_NEW);
        schedule.setLockUntil(now.plusMinutes(properties.getLockTtlMinutes()));
        insertSchedule(schedule);
        return schedule;
    }

    /**
     * 旧逻辑（LEGACY，回退预案）：完全不做重叠/缓冲拦截，登记即确认，长期占位。
     * 与作业站过去手工台账口径一致：只登记、不防撞；其余取消/查询仍走系统。
     */
    @Transactional(rollbackFor = Exception.class)
    public MachineSchedule createLegacy(ScheduleCreateRequest req, LocalDateTime now) {
        requestRecordService.register(req.getRequestNo(), BizType.MACHINE_SCHEDULE_CREATE, req.getScheduleNo());
        Machine machine = requireActiveMachine(req.getMachineId());
        LocalDateTime start = parseTime(req.getStartTime(), "开始时间");
        LocalDateTime end = parseTime(req.getEndTime(), "结束时间");
        if (!end.isAfter(start)) {
            throw new BusinessException("结束时间必须晚于开始时间");
        }

        MachineSchedule schedule = baseSchedule(req, machine, start, end);
        schedule.setStatus(ScheduleConstants.STATUS_CONFIRMED);
        schedule.setSource(ScheduleConstants.SOURCE_LEGACY);
        schedule.setConfirmTime(now);
        schedule.setLockUntil(null);
        insertSchedule(schedule);
        return schedule;
    }

    /** 确认：PENDING → CONFIRMED，长期占位，解除 35 分钟临时锁。 */
    @Transactional(rollbackFor = Exception.class)
    public MachineSchedule confirm(Long id, ScheduleConfirmRequest req, LocalDateTime now) {
        requestRecordService.register(req.getRequestNo(), BizType.MACHINE_SCHEDULE_CONFIRM, String.valueOf(id));
        expireForMachineId(id, now);

        MachineSchedule schedule = requireSchedule(id);
        if (!ScheduleConstants.STATUS_PENDING.equals(schedule.getStatus())) {
            throw new BusinessException("预约当前状态为 " + schedule.getStatus() + "，不能确认（仅待确认可确认）");
        }
        schedule.setStatus(ScheduleConstants.STATUS_CONFIRMED);
        schedule.setConfirmTime(now);
        schedule.setLockUntil(null);
        if (req.getOperatorCode() != null) {
            schedule.setOperatorCode(req.getOperatorCode());
        }
        if (req.getRemark() != null) {
            schedule.setRemark(req.getRemark());
        }
        if (scheduleMapper.updateById(schedule) == 0) {
            throw new BusinessException("预约状态已变化，请刷新后重试");
        }
        return schedule;
    }

    /** 取消：PENDING/CONFIRMED → CANCELLED，立即释放占位，该时段可再次预约。 */
    @Transactional(rollbackFor = Exception.class)
    public MachineSchedule cancel(Long id, ScheduleCancelRequest req, LocalDateTime now) {
        requestRecordService.register(req.getRequestNo(), BizType.MACHINE_SCHEDULE_CANCEL, String.valueOf(id));

        MachineSchedule schedule = requireSchedule(id);
        String status = schedule.getStatus();
        if (ScheduleConstants.STATUS_CANCELLED.equals(status)
                || ScheduleConstants.STATUS_EXPIRED.equals(status)) {
            throw new BusinessException("预约已" + (ScheduleConstants.STATUS_CANCELLED.equals(status) ? "取消" : "过期释放")
                    + "，无需重复取消");
        }
        schedule.setStatus(ScheduleConstants.STATUS_CANCELLED);
        schedule.setCancelTime(now);
        schedule.setCancelReason(req.getCancelReason());
        schedule.setLockUntil(null);
        if (scheduleMapper.updateById(schedule) == 0) {
            throw new BusinessException("预约状态已变化，请刷新后重试");
        }
        return schedule;
    }

    /**
     * 释放某台农机所有已过 lock_until 的待确认占位（创建/确认前惰性调用）。
     * 条件更新只命中 PENDING 且到期的记录，已确认/取消的不受影响。
     */
    @Transactional(rollbackFor = Exception.class)
    public int expireForMachine(Long machineId, LocalDateTime now) {
        return scheduleMapper.update(null, new LambdaUpdateWrapper<MachineSchedule>()
                .eq(MachineSchedule::getMachineId, machineId)
                .eq(MachineSchedule::getStatus, ScheduleConstants.STATUS_PENDING)
                .le(MachineSchedule::getLockUntil, now)
                .set(MachineSchedule::getStatus, ScheduleConstants.STATUS_EXPIRED)
                .set(MachineSchedule::getExpireTime, now)
                .set(MachineSchedule::getLockUntil, null));
    }

    /** 定时/手工全局释放：所有到期的待确认占位自动放掉。返回释放条数。 */
    @Transactional(rollbackFor = Exception.class)
    public int releaseExpired(LocalDateTime now) {
        return scheduleMapper.update(null, new LambdaUpdateWrapper<MachineSchedule>()
                .eq(MachineSchedule::getStatus, ScheduleConstants.STATUS_PENDING)
                .le(MachineSchedule::getLockUntil, now)
                .set(MachineSchedule::getStatus, ScheduleConstants.STATUS_EXPIRED)
                .set(MachineSchedule::getExpireTime, now)
                .set(MachineSchedule::getLockUntil, null));
    }

    // ---- internals ----

    /**
     * 逐条防撞判定。真重叠（一端结束晚于另一端开始）即冲突；
     * 首尾相接（间隔为 0）允许；相邻间隔在 (0, buffer) 分钟内拒绝，恰好 buffer 分钟允许。
     */
    private void assertNoClash(String selfNo, LocalDateTime start, LocalDateTime end,
                               List<MachineSchedule> occupied) {
        long bufferSeconds = properties.getBufferMinutes() * 60L;
        for (MachineSchedule other : occupied) {
            LocalDateTime oStart = other.getStartTime();
            LocalDateTime oEnd = other.getEndTime();
            boolean overlap = start.isBefore(oEnd) && oStart.isBefore(end);
            if (overlap) {
                throw new BusinessException("农机在 " + fmt(oStart) + "~" + fmt(oEnd)
                        + " 已有预约 " + other.getScheduleNo() + "，时间重叠，请改约其它时段");
            }
            // 不重叠：计算两班相邻间隔（秒）。新班排在后面则 oEnd->start，否则 end->oStart。
            long gapSeconds = !start.isBefore(oEnd)
                    ? Duration.between(oEnd, start).getSeconds()
                    : Duration.between(end, oStart).getSeconds();
            if (gapSeconds > 0 && gapSeconds < bufferSeconds) {
                throw new BusinessException("与预约 " + other.getScheduleNo() + "（" + fmt(oStart) + "~" + fmt(oEnd)
                        + "）间隔不足 " + properties.getBufferMinutes() + " 分钟缓冲，首尾相接可以，但需留出缓冲");
            }
        }
    }

    private void expireForMachineId(Long scheduleId, LocalDateTime now) {
        MachineSchedule schedule = scheduleMapper.selectById(scheduleId);
        if (schedule != null) {
            expireForMachine(schedule.getMachineId(), now);
        }
    }

    private Machine requireActiveMachine(Long machineId) {
        Machine machine = machineMapper.selectById(machineId);
        if (machine == null) {
            throw new BusinessException("农机不存在: " + machineId);
        }
        if (!MachineStatus.ACTIVE.equals(machine.getStatus())) {
            throw new BusinessException("农机已停用，不能排班");
        }
        return machine;
    }

    private MachineSchedule requireSchedule(Long id) {
        MachineSchedule schedule = scheduleMapper.selectById(id);
        if (schedule == null) {
            throw new BusinessException("排班预约不存在: " + id);
        }
        return schedule;
    }

    private MachineSchedule baseSchedule(ScheduleCreateRequest req, Machine machine,
                                         LocalDateTime start, LocalDateTime end) {
        MachineSchedule schedule = new MachineSchedule();
        schedule.setScheduleNo(req.getScheduleNo());
        schedule.setMachineId(machine.getId());
        schedule.setWorkOrderNo(req.getWorkOrderNo());
        schedule.setOperatorCode(req.getOperatorCode());
        schedule.setPlotName(req.getPlotName());
        schedule.setStartTime(start);
        schedule.setEndTime(end);
        schedule.setRemark(req.getRemark());
        schedule.setRequestNo(req.getRequestNo());
        return schedule;
    }

    private void insertSchedule(MachineSchedule schedule) {
        try {
            scheduleMapper.insert(schedule);
        } catch (DuplicateKeyException e) {
            throw new BusinessException("预约单号 " + schedule.getScheduleNo() + " 已存在");
        }
    }

    static LocalDateTime parseTime(String text, String field) {
        if (text == null || text.trim().isEmpty()) {
            throw new BusinessException(field + "不能为空");
        }
        String value = text.trim();
        try {
            return LocalDateTime.parse(value, TIME_FMT);
        } catch (DateTimeParseException e) {
            try {
                return LocalDateTime.parse(value);
            } catch (DateTimeParseException e2) {
                throw new BusinessException(field + "格式应为 yyyy-MM-dd HH:mm:ss");
            }
        }
    }

    private String fmt(LocalDateTime t) {
        return t == null ? "" : t.format(TIME_FMT);
    }
}
