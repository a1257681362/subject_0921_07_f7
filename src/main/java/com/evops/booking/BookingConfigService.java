package com.evops.booking;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.booking.entity.BookingConfig;
import com.evops.booking.mapper.BookingConfigMapper;
import com.evops.common.BizType;
import com.evops.common.BusinessException;
import com.evops.request.RequestRecordService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 排班运行期配置：application.yml 给默认值，t_booking_config 存在线调整（重启不丢）。
 * 口径模式/缓冲分钟/临时锁分钟的变更都走请求号台账。
 */
@Service
public class BookingConfigService {

    public static final String KEY_BOOKING_MODE = "BOOKING_MODE";
    public static final String KEY_BUFFER_MINUTES = "BUFFER_MINUTES";
    public static final String KEY_LOCK_TTL_MINUTES = "LOCK_TTL_MINUTES";

    private final BookingConfigMapper configMapper;
    private final BookingProperties properties;
    private final RequestRecordService requestRecordService;

    public BookingConfigService(BookingConfigMapper configMapper,
                                BookingProperties properties,
                                RequestRecordService requestRecordService) {
        this.configMapper = configMapper;
        this.properties = properties;
        this.requestRecordService = requestRecordService;
    }

    public String getMode() {
        String value = get(KEY_BOOKING_MODE);
        return value == null ? properties.getMode() : value;
    }

    public int getBufferMinutes() {
        String value = get(KEY_BUFFER_MINUTES);
        return value == null ? properties.getBufferMinutes() : Integer.parseInt(value);
    }

    public int getLockTtlMinutes() {
        String value = get(KEY_LOCK_TTL_MINUTES);
        return value == null ? properties.getLockTtlMinutes() : Integer.parseInt(value);
    }

    /** 当前生效口径与参数（含调度开关与扫描 cron）。 */
    public com.evops.booking.vo.BookingConfigView currentView() {
        com.evops.booking.vo.BookingConfigView view = new com.evops.booking.vo.BookingConfigView();
        view.setMode(getMode());
        view.setBufferMinutes(getBufferMinutes());
        view.setLockTtlMinutes(getLockTtlMinutes());
        view.setSchedulingEnabled(properties.isSchedulingEnabled());
        view.setExpireScanCron(properties.getExpireScanCron());
        return view;
    }

    /** 切换防撞口径：NEW 新 / LEGACY 旧（异常回退）/ PARALLEL 并跑。 */
    @Transactional(rollbackFor = Exception.class)
    public String updateMode(String mode, String requestNo) {
        if (!BookingMode.isValid(mode)) {
            throw new BusinessException("未知排班口径模式: " + mode);
        }
        requestRecordService.register(requestNo, BizType.BOOKING_CONFIG_UPDATE,
                KEY_BOOKING_MODE + "=" + mode);
        put(KEY_BOOKING_MODE, mode);
        return mode;
    }

    /** 在线调整缓冲分钟与临时锁分钟（任一非空才更新对应项）。 */
    @Transactional(rollbackFor = Exception.class)
    public void updateParams(Integer bufferMinutes, Integer lockTtlMinutes, String requestNo) {
        if (bufferMinutes == null && lockTtlMinutes == null) {
            throw new BusinessException("缓冲分钟与临时锁分钟至少要传一项");
        }
        if (bufferMinutes != null && bufferMinutes < 0) {
            throw new BusinessException("相邻缓冲分钟不能为负");
        }
        if (lockTtlMinutes != null && lockTtlMinutes <= 0) {
            throw new BusinessException("临时锁定分钟必须大于 0");
        }
        requestRecordService.register(requestNo, BizType.BOOKING_CONFIG_UPDATE,
                "buffer=" + bufferMinutes + ",lockTtl=" + lockTtlMinutes);
        if (bufferMinutes != null) {
            put(KEY_BUFFER_MINUTES, String.valueOf(bufferMinutes));
        }
        if (lockTtlMinutes != null) {
            put(KEY_LOCK_TTL_MINUTES, String.valueOf(lockTtlMinutes));
        }
    }

    private String get(String key) {
        BookingConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<BookingConfig>().eq(BookingConfig::getConfigKey, key));
        return config == null ? null : config.getConfigValue();
    }

    private void put(String key, String value) {
        BookingConfig config = configMapper.selectOne(
                new LambdaQueryWrapper<BookingConfig>().eq(BookingConfig::getConfigKey, key));
        if (config == null) {
            config = new BookingConfig();
            config.setConfigKey(key);
            config.setConfigValue(value);
            configMapper.insert(config);
        } else {
            config.setConfigValue(value);
            configMapper.updateById(config);
        }
    }
}
