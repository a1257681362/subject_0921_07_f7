package com.evops.schedule.controller;

import com.evops.common.ApiResponse;
import com.evops.schedule.MachineScheduleService;
import com.evops.schedule.ScheduleConfigService;
import com.evops.schedule.dto.ScheduleCancelRequest;
import com.evops.schedule.dto.ScheduleConfirmRequest;
import com.evops.schedule.dto.ScheduleModeUpdateRequest;
import com.evops.schedule.dto.ScheduleCreateRequest;
import com.evops.schedule.entity.MachineSchedule;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 农机排班防撞预约接口。
 * 异常时 POST /api/machine-schedules/config/engine-mode 传 LEGACY 一键切回旧手工台账逻辑。
 */
@RestController
@RequestMapping("/api/machine-schedules")
public class MachineScheduleController {

    private final MachineScheduleService scheduleService;
    private final ScheduleConfigService configService;

    public MachineScheduleController(MachineScheduleService scheduleService,
                                     ScheduleConfigService configService) {
        this.scheduleService = scheduleService;
        this.configService = configService;
    }

    /** 新增预约：NEW 下查重叠与 18 分钟缓冲，落 PENDING 临时锁 35 分钟；LEGACY 下登记即确认。 */
    @PostMapping
    public ApiResponse<MachineSchedule> create(@Valid @RequestBody ScheduleCreateRequest request) {
        return ApiResponse.ok(scheduleService.create(request));
    }

    /** 确认预约：PENDING → CONFIRMED。 */
    @PostMapping("/{id}/confirm")
    public ApiResponse<MachineSchedule> confirm(@PathVariable Long id,
                                                 @Valid @RequestBody ScheduleConfirmRequest request) {
        return ApiResponse.ok(scheduleService.confirm(id, request));
    }

    /** 取消预约：立即释放占位，该时段可再次预约。 */
    @PostMapping("/{id}/cancel")
    public ApiResponse<MachineSchedule> cancel(@PathVariable Long id,
                                                @Valid @RequestBody ScheduleCancelRequest request) {
        return ApiResponse.ok(scheduleService.cancel(id, request));
    }

    /** 手工释放所有已过 35 分钟未确认的占位（定时任务之外的兜底入口）。 */
    @PostMapping("/release-expired")
    public ApiResponse<Map<String, Object>> releaseExpired() {
        int released = scheduleService.releaseExpired();
        Map<String, Object> data = new HashMap<>();
        data.put("released", released);
        return ApiResponse.ok(data);
    }

    /** 预约详情。 */
    @GetMapping("/{id}")
    public ApiResponse<MachineSchedule> get(@PathVariable Long id) {
        return ApiResponse.ok(scheduleService.get(id));
    }

    /** 列表：可按农机、日期（开始时间当天）、状态过滤。 */
    @GetMapping
    public ApiResponse<List<MachineSchedule>> list(
            @RequestParam(required = false) Long machineId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(scheduleService.list(machineId, date, status));
    }

    /** 当前防撞模式与缓冲/锁定参数。 */
    @GetMapping("/config")
    public ApiResponse<Map<String, Object>> getConfig() {
        Map<String, Object> map = new HashMap<>();
        map.put("engineMode", configService.getEngineMode());
        return ApiResponse.ok(map);
    }

    /** 在线切换防撞模式（NEW/LEGACY），异常一键切回旧逻辑。 */
    @PostMapping("/config/engine-mode")
    public ApiResponse<Map<String, Object>> updateEngineMode(@Valid @RequestBody ScheduleModeUpdateRequest request) {
        String mode = configService.updateEngineMode(request.getEngineMode(), request.getRequestNo());
        Map<String, Object> map = new HashMap<>();
        map.put("engineMode", mode);
        return ApiResponse.ok(map);
    }
}
