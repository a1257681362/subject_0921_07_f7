package com.evops.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 取消预约：HELD/CONFIRMED→CANCELLED，立即释放时段、可再约，历史保留。 */
@Data
public class AppointmentCancelRequest {
    @NotNull(message = "版本号不能为空")
    private Integer version;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    /** 取消原因。 */
    private String cancelReason;
}
