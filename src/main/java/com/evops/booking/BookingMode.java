package com.evops.booking;

/** 排班防撞口径模式（与勾稽新旧引擎并跑同思路）。 */
public final class BookingMode {
    /** 新口径：严格重叠拦截 + 相邻缓冲分钟内不许插单（首尾相接豁免）。 */
    public static final String NEW = "NEW";
    /** 旧口径（手工台账基线）：仅严格时段重叠才拦，不看缓冲。异常时一键切回。 */
    public static final String LEGACY = "LEGACY";
    /** 并跑：以新口径受理，另按旧口径记账比对（差异只告警不拦截）。 */
    public static final String PARALLEL = "PARALLEL";

    public static boolean isValid(String mode) {
        return NEW.equals(mode) || LEGACY.equals(mode) || PARALLEL.equals(mode);
    }

    private BookingMode() {
    }
}
