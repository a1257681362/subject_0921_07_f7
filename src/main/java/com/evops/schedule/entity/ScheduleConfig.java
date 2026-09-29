package com.evops.schedule.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.Version;
import com.evops.common.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

/** 排班运行期配置键值（ENGINE_MODE），在线切回旧逻辑后重启不丢。 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("t_machine_schedule_config")
public class ScheduleConfig extends BaseEntity {
    public static final String KEY_ENGINE_MODE = "ENGINE_MODE";

    private String configKey;
    private String configValue;

    @Version
    private Integer version = 0;
}
