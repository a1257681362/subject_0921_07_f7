package com.evops.schedule;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 排班过期待确认占位的定时自动释放：PENDING 超过 lock_until（默认35分钟）仍未确认即自动置 EXPIRED。
 * 即便错过调度，新建/确认前还有按机惰性释放兜底。测试环境 schedule.scheduling-enabled=false 关闭。
 */
@Component
@ConditionalOnProperty(prefix = "schedule", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class ScheduleLockReaper {

    private final MachineScheduleService scheduleService;

    public ScheduleLockReaper(MachineScheduleService scheduleService) {
        this.scheduleService = scheduleService;
    }

    @Scheduled(cron = "${schedule.cleanup-cron:0 * * * * ?}")
    public void releaseExpiredLocks() {
        scheduleService.releaseExpiredScheduled();
    }
}
