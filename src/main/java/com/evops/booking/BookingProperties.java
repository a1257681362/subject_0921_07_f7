package com.evops.booking;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 排班防撞配置（application.yml 中 booking 前缀）。 */
@Component
@ConfigurationProperties(prefix = "booking")
public class BookingProperties {

    /** 过期锁自动扫描开关，测试环境可关闭改为手工触发。 */
    private boolean schedulingEnabled = true;
    /** 自动扫描过期临时锁的 cron，默认每分钟一次。 */
    private String expireScanCron = "0 * * * * ?";
    /** 防撞口径：NEW 新口径 / LEGACY 旧口径（回退）/ PARALLEL 并跑记账。 */
    private String mode = BookingMode.NEW;
    /** 相邻预约之间的最小缓冲分钟，间隔小于该值不许插单（首尾相接豁免）。 */
    private int bufferMinutes = 18;
    /** HELD 临时锁定保留分钟，超时未确认自动释放。 */
    private int lockTtlMinutes = 35;

    public boolean isSchedulingEnabled() { return schedulingEnabled; }
    public void setSchedulingEnabled(boolean schedulingEnabled) { this.schedulingEnabled = schedulingEnabled; }
    public String getExpireScanCron() { return expireScanCron; }
    public void setExpireScanCron(String expireScanCron) { this.expireScanCron = expireScanCron; }
    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }
    public int getBufferMinutes() { return bufferMinutes; }
    public void setBufferMinutes(int bufferMinutes) { this.bufferMinutes = bufferMinutes; }
    public int getLockTtlMinutes() { return lockTtlMinutes; }
    public void setLockTtlMinutes(int lockTtlMinutes) { this.lockTtlMinutes = lockTtlMinutes; }
}
