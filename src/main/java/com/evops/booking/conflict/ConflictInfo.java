package com.evops.booking.conflict;

import com.evops.booking.entity.MachineAppointment;

import java.time.LocalDateTime;

/** 冲突描述：与谁冲突、冲突类型与可读说明。 */
public class ConflictInfo {

    /** 严格时段重叠（两区间真正相交）。 */
    public static final String OVERLAP = "OVERLAP";
    /** 不重叠但相邻间隔小于缓冲分钟（首尾相接不算）。 */
    public static final String BUFFER = "BUFFER";

    private final MachineAppointment against;
    private final String type;
    private final String message;

    public ConflictInfo(MachineAppointment against, String type, String message) {
        this.against = against;
        this.type = type;
        this.message = message;
    }

    public static ConflictInfo overlap(MachineAppointment a) {
        return new ConflictInfo(a, OVERLAP,
                "与预约 " + a.getAppointmentNo() + "（" + a.getStartTime() + " ~ " + a.getEndTime()
                        + "，" + a.getStatus() + "）时段重叠");
    }

    public static ConflictInfo buffer(MachineAppointment a, long gapMinutes, int bufferMinutes) {
        return new ConflictInfo(a, BUFFER,
                "与预约 " + a.getAppointmentNo() + "（" + a.getStartTime() + " ~ " + a.getEndTime()
                        + "）仅相隔 " + gapMinutes + " 分钟，不足 " + bufferMinutes
                        + " 分钟缓冲（首尾相接除外）");
    }

    public MachineAppointment getAgainst() { return against; }
    public String getType() { return type; }
    public String getMessage() { return message; }
}
