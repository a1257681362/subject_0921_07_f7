package com.evops.booking.schedule;

import com.evops.booking.AppointmentService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 临时锁过期自动扫描：到点未确认的 HELD 预约逐笔独立事务翻成 EXPIRED 释放。
 * 异常只记录不外抛，下一轮继续；测试环境用 booking.scheduling-enabled=false 关闭，
 * 由用例直接调 {@link AppointmentService#sweepExpiredLocks()} 触发。
 */
@Component
@ConditionalOnProperty(prefix = "booking", name = "scheduling-enabled", havingValue = "true",
        matchIfMissing = true)
public class AppointmentExpireScheduler {

    private static final Logger log = LoggerFactory.getLogger(AppointmentExpireScheduler.class);

    private final AppointmentService appointmentService;

    public AppointmentExpireScheduler(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    @Scheduled(cron = "${booking.expire-scan-cron:0 * * * * ?}")
    public void sweep() {
        try {
            int released = appointmentService.sweepExpiredLocks();
            if (released > 0) {
                log.info("临时锁过期自动释放：{} 笔", released);
            }
        } catch (Exception e) {
            log.error("预约临时锁过期扫描失败：{}", e.getMessage(), e);
        }
    }
}
