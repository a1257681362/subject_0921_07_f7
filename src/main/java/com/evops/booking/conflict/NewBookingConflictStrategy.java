package com.evops.booking.conflict;

import com.evops.booking.BookingMode;
import com.evops.booking.entity.MachineAppointment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 新口径（防手工台账错漏）：先拦严格时段重叠；不重叠再查相邻缓冲——
 * 间隔不足 bufferMinutes 不许插单，首尾相接（间隔为 0）豁免。
 */
@Component
public class NewBookingConflictStrategy extends AbstractConflictStrategy {

    @Override
    public String code() {
        return BookingMode.NEW;
    }

    @Override
    public ConflictInfo check(List<MachineAppointment> occupied, LocalDateTime start, LocalDateTime end,
                              int bufferMinutes, LocalDateTime now) {
        ConflictInfo overlap = firstOverlap(occupied, start, end);
        if (overlap != null) {
            return overlap;
        }
        for (MachineAppointment a : occupied) {
            ConflictInfo buffer = bufferConflict(a, start, end, bufferMinutes);
            if (buffer != null) {
                return buffer;
            }
        }
        return null;
    }
}
