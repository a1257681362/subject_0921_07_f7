package com.evops.schedule.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 确认排班预约：PENDING → CONFIRMED。 */
@Data
public class ScheduleConfirmRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String operatorCode;
    private String remark;
}
