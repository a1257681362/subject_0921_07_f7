package com.evops.booking.vo;

import com.evops.booking.entity.MachineAppointment;
import com.evops.booking.entity.MachineAppointmentLog;
import lombok.Data;

import java.util.List;

/** 预约详情：单头 + 完整状态流转历史。 */
@Data
public class AppointmentDetail {
    private MachineAppointment appointment;
    private List<MachineAppointmentLog> logs;
}
