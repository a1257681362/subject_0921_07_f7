package com.evops.booking.controller;

import com.evops.booking.AppointmentService;
import com.evops.booking.BookingConfigService;
import com.evops.booking.dto.AppointmentBookRequest;
import com.evops.booking.dto.AppointmentCancelRequest;
import com.evops.booking.dto.AppointmentConfirmRequest;
import com.evops.booking.dto.BookingModeUpdateRequest;
import com.evops.booking.dto.BookingParamsUpdateRequest;
import com.evops.booking.entity.MachineAppointment;
import com.evops.booking.vo.AppointmentDetail;
import com.evops.booking.vo.BookingConfigView;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;
import java.util.List;

/**
 * 农机预约排班接口。
 * 新增先防撞（重叠/缓冲），提交即 HELD 临时锁（35 分钟），确认长期占用，
 * 取消/过期立即释放可再约；口径可一键 LEGACY 回退旧逻辑。
 */
@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;
    private final BookingConfigService configService;

    public AppointmentController(AppointmentService appointmentService,
                                 BookingConfigService configService) {
        this.appointmentService = appointmentService;
        this.configService = configService;
    }

    @PostMapping
    public ApiResponse<MachineAppointment> book(@Valid @RequestBody AppointmentBookRequest request) {
        return ApiResponse.ok(appointmentService.book(request));
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<MachineAppointment> confirm(@PathVariable Long id,
                                                   @Valid @RequestBody AppointmentConfirmRequest request) {
        return ApiResponse.ok(appointmentService.confirm(id, request));
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<MachineAppointment> cancel(@PathVariable Long id,
                                                  @Valid @RequestBody AppointmentCancelRequest request) {
        return ApiResponse.ok(appointmentService.cancel(id, request));
    }

    @GetMapping("/{id}")
    public ApiResponse<AppointmentDetail> detail(@PathVariable Long id) {
        return ApiResponse.ok(appointmentService.detail(id));
    }

    @GetMapping
    public ApiResponse<List<MachineAppointment>> list(@RequestParam(required = false) Long machineId,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false, defaultValue = "100") int limit) {
        return ApiResponse.ok(appointmentService.list(machineId, status, limit));
    }

    /** 手工触发一轮过期临时锁扫描（值班处置/测试用），返回释放笔数。 */
    @PostMapping("/sweep-expired")
    public ApiResponse<Integer> sweepExpired() {
        return ApiResponse.ok(appointmentService.sweepExpiredLocks());
    }

    /** 查看当前排班口径与参数。 */
    @GetMapping("/config")
    public ApiResponse<BookingConfigView> getConfig() {
        return ApiResponse.ok(configService.currentView());
    }

    /** 切换防撞口径（NEW/LEGACY/PARALLEL），异常立即切回 LEGACY。 */
    @PostMapping("/config/mode")
    public ApiResponse<String> updateMode(@Valid @RequestBody BookingModeUpdateRequest request) {
        return ApiResponse.ok(configService.updateMode(request.getMode(), request.getRequestNo()));
    }

    /** 在线调整缓冲分钟 / 临时锁分钟。 */
    @PostMapping("/config/params")
    public ApiResponse<BookingConfigView> updateParams(@Valid @RequestBody BookingParamsUpdateRequest request) {
        configService.updateParams(request.getBufferMinutes(), request.getLockTtlMinutes(),
                request.getRequestNo());
        return ApiResponse.ok(configService.currentView());
    }
}
