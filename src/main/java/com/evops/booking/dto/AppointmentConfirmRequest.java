package com.evops.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

/** 预约确认：HELD→CONFIRMED，凭版本乐观锁；确认后长期占用，不再受临时锁过期影响。 */
@Data
public class AppointmentConfirmRequest {
    @NotNull(message = "版本号不能为空")
    private Integer version;
    @NotBlank(message = "操作人不能为空")
    private String operator;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
    private String remark;
}
