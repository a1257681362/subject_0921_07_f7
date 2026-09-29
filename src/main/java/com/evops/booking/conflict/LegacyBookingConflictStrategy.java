package com.evops.booking.conflict;

import com.evops.booking.BookingMode;
import com.evops.booking.entity.MachineAppointment;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 旧口径（手工台账基线 / 回退预案）：仅严格时段重叠才拦，
 * 不做相邻缓冲校验——异常时一键切回，保证排班业务不中断。
 */
@Component
public class LegacyBookingConflictStrategy extends AbstractConflictStrategy {

    @Override
    public String code() {
        return BookingMode.LEGACY;
    }

    @Override
    public ConflictInfo check(List<MachineAppointment> occupied, LocalDateTime start, LocalDateTime end,
                              int bufferMinutes, LocalDateTime now) {
        return firstOverlap(occupied, start, end);
    }
}
