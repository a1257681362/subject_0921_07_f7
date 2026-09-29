package com.evops.booking;

/** 农机预约排班状态。 */
public final class AppointmentStatus {
    /** 临时锁定：预约提交后未确认，只保留 lockTtlMinutes（默认 35 分钟）。 */
    public static final String HELD = "HELD";
    /** 已确认：长期占用时段。 */
    public static final String CONFIRMED = "CONFIRMED";
    /** 已取消：立即释放时段，历史保留不物理删除，时段可再约。 */
    public static final String CANCELLED = "CANCELLED";
    /** 已过期：临时锁超时未确认，由定时任务/惰性检查自动释放。 */
    public static final String EXPIRED = "EXPIRED";

    private AppointmentStatus() {
    }
}
