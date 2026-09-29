package com.evops.booking.conflict;

import com.evops.booking.entity.MachineAppointment;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

/** 公共严格重叠判定：两区间真正相交才算冲突；端点相等（首尾相接）不算。 */
public abstract class AbstractConflictStrategy implements BookingConflictStrategy {

    /** [start,end) 与预约窗口是否真正相交（端点相接不算）。 */
    protected boolean overlaps(MachineAppointment a, LocalDateTime start, LocalDateTime end) {
        return start.isBefore(a.getEndTime()) && a.getStartTime().isBefore(end);
    }

    /**
     * 新口径缓冲判定（调用前已确认两窗口不重叠）：
     * gap=0（首尾相接）放行；0 &lt; gap &lt; bufferMinutes 判缓冲冲突；其余放行。
     */
    protected ConflictInfo bufferConflict(MachineAppointment a, LocalDateTime start, LocalDateTime end,
                                          int bufferMinutes) {
        Duration gap;
        if (!end.isAfter(a.getStartTime())) {
            // 新窗口在前：end <= 既有开始，相等即首尾相接
            gap = Duration.between(end, a.getStartTime());
        } else {
            // 不重叠则新窗口必在后：start >= 既有结束
            gap = Duration.between(a.getEndTime(), start);
        }
        long gapMinutes = gap.toMinutes();
        if (!gap.isZero() && gap.compareTo(Duration.ofMinutes(bufferMinutes)) < 0) {
            return ConflictInfo.buffer(a, gapMinutes, bufferMinutes);
        }
        return null;
    }

    protected ConflictInfo firstOverlap(List<MachineAppointment> occupied,
                                        LocalDateTime start, LocalDateTime end) {
        for (MachineAppointment a : occupied) {
            if (overlaps(a, start, end)) {
                return ConflictInfo.overlap(a);
            }
        }
        return null;
    }
}
