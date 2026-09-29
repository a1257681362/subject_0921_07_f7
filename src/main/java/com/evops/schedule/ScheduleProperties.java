package com.evops.schedule;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 农机排班防撞配置（application.yml 中 schedule 前缀）。 */
@Component
@ConfigurationProperties(prefix = "schedule")
public class ScheduleProperties {

    /** 过期待确认预约定时自动释放开关，测试环境关闭。 */
    private boolean schedulingEnabled = true;
    /** 相邻两班最小间隔（分钟）：间隔 < buffer 不许插，首尾相接与恰好 buffer 分钟允许。 */
    private int bufferMinutes = 18;
    /** 待确认预约临时占位时长（分钟）：PENDING 只锁这么久，过期自动放掉。 */
    private int lockTtlMinutes = 35;
    /** NEW 系统防撞 / LEGACY 旧手工台账逻辑（回退用）。 */
    private String engineMode = "NEW";
    /** 过期临时锁自动释放扫描 cron。 */
    private String cleanupCron = "0 * * * * ?";

    public boolean isSchedulingEnabled() { return schedulingEnabled; }
    public void setSchedulingEnabled(boolean schedulingEnabled) { this.schedulingEnabled = schedulingEnabled; }
    public int getBufferMinutes() { return bufferMinutes; }
    public void setBufferMinutes(int bufferMinutes) { this.bufferMinutes = bufferMinutes; }
    public int getLockTtlMinutes() { return lockTtlMinutes; }
    public void setLockTtlMinutes(int lockTtlMinutes) { this.lockTtlMinutes = lockTtlMinutes; }
    public String getEngineMode() { return engineMode; }
    public void setEngineMode(String engineMode) { this.engineMode = engineMode; }
    public String getCleanupCron() { return cleanupCron; }
    public void setCleanupCron(String cleanupCron) { this.cleanupCron = cleanupCron; }
}
