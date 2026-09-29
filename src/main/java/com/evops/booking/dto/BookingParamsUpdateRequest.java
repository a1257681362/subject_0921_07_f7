package com.evops.booking.dto;

import lombok.Data;

import javax.validation.constraints.NotBlank;

/** 在线调整缓冲分钟 / 临时锁分钟（字段为空则不调整该项）。 */
@Data
public class BookingParamsUpdateRequest {
    /** 相邻缓冲分钟（默认 18）。 */
    private Integer bufferMinutes;
    /** HELD 临时锁定分钟（默认 35）。 */
    private Integer lockTtlMinutes;
    @NotBlank(message = "请求号不能为空")
    private String requestNo;
}
