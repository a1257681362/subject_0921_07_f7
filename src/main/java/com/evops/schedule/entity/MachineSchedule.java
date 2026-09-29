package com.evops.schedule.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

/**
 * 农机排班预约。同一台农机的时间区间在系统内统一防撞：
 * PENDING 待确认临时占位（lock_until 前参与防撞，过期自动释放）；
 * CONFIRMED 已确认长期占位；CANCELLED/EXPIRED 已释放，不再参与防撞。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_machine_schedule")
public class MachineSchedule extends BaseEntity {
    private String scheduleNo;
    private Long machineId;
    /** 关联作业单号（可空，先排后建档）。 */
    private String workOrderNo;
    private String operatorCode;
    private String plotName;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    /** PENDING / CONFIRMED / CANCELLED / EXPIRED。 */
    private String status;
    /** PENDING 临时占位截止时刻；过期即不再防撞并自动置 EXPIRED。 */
    private LocalDateTime lockUntil;
    private LocalDateTime confirmTime;
    private LocalDateTime cancelTime;
    private String cancelReason;
    private LocalDateTime expireTime;
    /** NEW / LEGACY：登记时所处模式，回退期旧逻辑登记为 LEGACY。 */
    private String source;
    private String requestNo;
    private String remark;

    @Version
    private Integer version = 0;
}
