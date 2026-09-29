package com.evops.booking.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.booking.AppointmentStatus;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 农机预约排班。HELD 临时锁定（lockDeadline 前未确认自动过期）→ CONFIRMED；
 * 任一进行态可 CANCELLED 立即释放。缓冲/锁时长在预约时快照，便于按当时口径追溯。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_machine_appointment")
public class MachineAppointment extends BaseEntity {
    private String appointmentNo;
    private Long machineId;
    private String plotName;
    private String operatorCode;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** HELD / CONFIRMED / CANCELLED / EXPIRED。 */
    private String status = AppointmentStatus.HELD;
    /** HELD 临时锁截止时间；超过即视为过期，可被惰性/定时释放。 */
    private LocalDateTime lockDeadline;
    /** 预约时生效的相邻缓冲分钟（默认 18）。 */
    private Integer bufferMinutes;
    /** 预约时生效的临时锁定分钟（默认 35）。 */
    private Integer lockTtlMinutes;
    private String cancelReason;
    private String requestNo;
    private String remark;

    @Version
    private Integer version = 0;
}
