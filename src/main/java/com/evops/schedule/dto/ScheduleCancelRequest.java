package com.evops.schedule.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 取消排班预约：立即释放占位，可被新预约再约。 */
@Data
public class ScheduleCancelRequest {
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String cancelReason;
}
