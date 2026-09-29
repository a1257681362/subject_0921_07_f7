package com.evops.schedule;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.request.RequestRecordService;
import com.evops.schedule.entity.ScheduleConfig;
import com.evops.schedule.mapper.ScheduleConfigMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排班防撞运行期配置：application.yml 给默认模式（NEW），t_machine_schedule_config
 * 存在线切换结果（重启不丢）。模式变更走请求号台账。
 * 异常时一键改成 LEGACY 即切回旧手工台账逻辑（不拦截、登记即确认）。
 */
@Service
public class ScheduleConfigService {

    private final ScheduleConfigMapper configMapper;
    private final ScheduleProperties properties;
    private final RequestRecordService requestRecordService;

    public ScheduleConfigService(ScheduleConfigMapper configMapper,
                                 ScheduleProperties properties,
                                 RequestRecordService requestRecordService) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.requestRecordService = requestRecordService;
    }

    /** 当前防撞模式：库内在线值优先，否则取配置默认值。 */
    public String getEngineMode() {
        String value = get(ScheduleConfig.KEY_ENGINE_MODE);
        return value == null ? properties.getEngineMode() : value;
    }

    public boolean isNewEngine() {
        return ScheduleConstants.MODE_NEW.equals(getEngineMode());
    }

    @Transactional(rollbackFor = Exception.class)
    public String updateEngineMode(String mode, String requestNo) {
        if (!ScheduleConstants.MODE_NEW.equals(mode) && !ScheduleConstants.MODE_LEGACY.equals(mode)) {
            throw new BusinessException("未知防撞模式: " + mode);
        }
        requestRecordService.register(requestNo, BizType.MACHINE_SCHEDULE_MODE_UPDATE,
                ScheduleConfig.KEY_ENGINE_MODE + "=" + mode);
        put(ScheduleConfig.KEY_ENGINE_MODE, mode);
        return mode;
    }

    private String get(String key) {
        ScheduleConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<ScheduleConfig>().eq(ScheduleConfig::getConfigKey, key));
        return config == null ? null : config.getConfigValue();
    }

    private void put(String key, String value) {
        ScheduleConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<ScheduleConfig>().eq(ScheduleConfig::getConfigKey, key));
        if (config == null) {
            config = new ScheduleConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            configMapper.insert(config);
        } else {
            config.setConfigValue(value);
            configMapper.updateById(config);
        }
    }
}
