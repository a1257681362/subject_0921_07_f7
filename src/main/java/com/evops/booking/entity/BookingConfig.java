package com.evops.booking.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 排班运行期配置（键值，覆盖 application.yml 默认值，重启不丢）。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_booking_config")
public class BookingConfig extends BaseEntity {
    /** BOOKING_MODE / BUFFER_MINUTES / LOCK_TTL_MINUTES。 */
    private String configKey;
    private String configValue;

    @Version
    private Integer version = 0;
}
