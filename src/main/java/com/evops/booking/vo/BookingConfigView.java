package com.evops.booking.vo;

import lombok.Data;

/** 当前排班口径与参数（含来源：默认/在线调整）。 */
@Data
public class BookingConfigView {
    private String mode;
    private int bufferMinutes;
    private int lockTtlMinutes;
    private boolean schedulingEnabled;
    private String expireScanCron;
}
