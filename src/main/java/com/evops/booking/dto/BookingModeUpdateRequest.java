package com.evops.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 切换防撞口径（NEW/LEGACY/PARALLEL），异常时立即切回 LEGACY 旧逻辑。 */
@Data
public class BookingModeUpdateRequest {
    @NotBlank(message = "排班口径模式不能为空")
    private String mode;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
