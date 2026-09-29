package com.evops.schedule;

/** 农机排班防撞相关状态与模式常量。 */
public final class ScheduleConstants {

    /** 预约状态。 */
    public static final String STATUS_PENDING = "PENDING";
    public static final String STATUS_CONFIRMED = "CONFIRMED";
    public static final String STATUS_CANCELLED = "CANCELLED";
    public static final String STATUS_EXPIRED = "EXPIRED";

    /** 防撞模式：NEW 系统防撞（新逻辑）；LEGACY 旧手工台账逻辑（不拦截、登记即确认，回退用）。 */
    public static final String MODE_NEW = "NEW";
    public static final String MODE_LEGACY = "LEGACY";

    /** 预约来源：NEW 新逻辑下的待确认/已确认；LEGACY 回退期登记即确认。 */
    public static final String SOURCE_NEW = "NEW";
    public static final String SOURCE_LEGACY = "LEGACY";

    private ScheduleConstants() {
    }
}
