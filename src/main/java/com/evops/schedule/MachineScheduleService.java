package com.evops.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BusinessException;
import com.evops.schedule.dto.ScheduleCancelRequest;
import com.evops.schedule.dto.ScheduleConfirmRequest;
import com.evops.schedule.dto.ScheduleCreateRequest;
import com.evops.schedule.entity.MachineSchedule;
import com.evops.schedule.mapper.MachineScheduleMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 农机排班防撞门面。
 *
 * 并发口径：同一台农机的新建/确认/取消在 JVM 内按农机分段锁串行，且分段锁在事务外层包裹，
 * 后一笔一定能读到前一笔已提交的占位（避免"先查后插"与"锁先于提交释放"的窗口）；
 * 预约单号唯一约束、请求号台账兜底跨提交重复。跨实例部署需上数据库排他/区间约束（见 runbook）。
 *
 * 回退口径：模式为 LEGACY 时新建不做任何防撞拦截、登记即确认（旧手工台账逻辑），
 * 一出异常可经配置接口一键切回。
 */
@Service
public class MachineScheduleService {

    private static final Logger log = LoggerFactory.getLogger(MachineScheduleService.class);
    /** 同一农机的并发操作分段锁，单机内串行。 */
    private static final int LOCK_STRIPES = 64;

    private final MachineScheduleTxOps txOps;
    private final ScheduleConfigService configService;
    private final MachineScheduleMapper scheduleMapper;
    private final ReentrantLock[] machineLocks = new ReentrantLock[LOCK_STRIPES];

    public MachineScheduleService(MachineScheduleTxOps txOps,
                                  ScheduleConfigService configService,
                                  MachineScheduleMapper scheduleMapper) {
        this.txOps = txOps;
        this.configService = configService;
        this.scheduleMapper = scheduleMapper;
        for (int i = 0; i < LOCK_STRIPES; i++) {
            machineLocks[i] = new ReentrantLock();
        }
    }

    /**
     * 新增预约。NEW：同机分段锁内查重叠与缓冲，落 PENDING 临时锁 35 分钟；
     * LEGACY：不拦截，登记即 CONFIRMED。
     */
    public MachineSchedule create(ScheduleCreateRequest req) {
        LocalDateTime now = LocalDateTime.now();
        if (configService.isNewEngine()) {
            ReentrantLock lock = lockForMachine(req.getMachineId());
            lock.lock();
            try {
                return txOps.createNew(req, now);
            } finally {
                lock.unlock();
            }
        }
        // 预案：切回旧逻辑，登记不防撞、即确认
        log.warn("排班防撞处于 LEGACY 旧逻辑模式，预约 {} 不做重叠/缓冲校验直接登记确认", req.getScheduleNo());
        return txOps.createLegacy(req, now);
    }

    /** 确认预约：临时占位转为长期占位。与新建同机串行，避免与过期释放相互穿插。 */
    public MachineSchedule confirm(Long id, ScheduleConfirmRequest req) {
        LocalDateTime now = LocalDateTime.now();
        Long machineId = machineIdOf(id);
        ReentrantLock lock = lockForMachine(machineId);
        lock.lock();
        try {
            return txOps.confirm(id, req, now);
        } finally {
            lock.unlock();
        }
    }

    /** 取消预约：立即释放，该时段可再次预约。 */
    public MachineSchedule cancel(Long id, ScheduleCancelRequest req) {
        LocalDateTime now = LocalDateTime.now();
        Long machineId = machineIdOf(id);
        ReentrantLock lock = lockForMachine(machineId);
        lock.lock();
        try {
            return txOps.cancel(id, req, now);
        } finally {
            lock.unlock();
        }
    }

    /** 手工触发：全局释放所有已过 35 分钟仍未确认的占位。返回释放条数。 */
    public int releaseExpired() {
        int released = txOps.releaseExpired(LocalDateTime.now());
        if (released > 0) {
            log.info("手工触发排班过期占位释放：{} 条", released);
        }
        return released;
    }

    /** 定时任务调用：异常只记录不外抛，避免调度线程中断。 */
    public void releaseExpiredScheduled() {
        try {
            int released = txOps.releaseExpired(LocalDateTime.now());
            if (released > 0) {
                log.info("定时释放排班过期待确认占位：{} 条", released);
            }
        } catch (Exception e) {
            log.error("排班过期占位定时释放失败：{}", e.getMessage(), e);
        }
    }

    public MachineSchedule get(Long id) {
        MachineSchedule schedule = scheduleMapper.selectById(id);
        if (schedule == null) {
            throw new BusinessException("排班预约不存在: " + id);
        }
        return schedule;
    }

    /** 列表：可按农机、日期（开始时间当天）、状态过滤。 */
    public List<MachineSchedule> list(Long machineId, LocalDate date, String status) {
        LambdaQueryWrapper<MachineSchedule> wrapper = new LambdaQueryWrapper<MachineSchedule>()
                .eq(machineId != null, MachineSchedule::getMachineId, machineId)
                .eq(status != null, MachineSchedule::getStatus, status)
                .orderByAsc(MachineSchedule::getStartTime)
                .orderByAsc(MachineSchedule::getId);
        if (date != null) {
            wrapper.ge(MachineSchedule::getStartTime, date.atStartOfDay())
                    .lt(MachineSchedule::getStartTime, date.plusDays(1).atStartOfDay());
        }
        return scheduleMapper.selectList(wrapper);
    }

    /** 取预约所属农机用于分段加锁；记录不存在时返回 0 号桩，真正的"不存在"由事务内抛错。 */
    private Long machineIdOf(Long scheduleId) {
        MachineSchedule schedule = scheduleMapper.selectById(scheduleId);
        return schedule == null ? 0L : schedule.getMachineId();
    }

    private ReentrantLock lockForMachine(Long machineId) {
        int hash = Math.floorMod(Long.hashCode(machineId), LOCK_STRIPES);
        return machineLocks[hash];
    }
}
