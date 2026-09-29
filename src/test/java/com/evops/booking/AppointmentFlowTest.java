package com.evops.booking;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.booking.dto.AppointmentBookRequest;
import com.evops.booking.dto.AppointmentCancelRequest;
import com.evops.booking.dto.AppointmentConfirmRequest;
import com.evops.booking.entity.MachineAppointment;
import com.evops.booking.mapper.MachineAppointmentMapper;
import com.evops.common.BusinessException;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 排班防撞主规则：重叠拦截、18 分钟缓冲（首尾相接豁免）、35 分钟临时锁过期释放、
 * 取消立即释放可再约、口径一键回退旧逻辑。
 */
@SpringBootTest
@ActiveProfiles("test")
class AppointmentFlowTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    @Autowired
    private AppointmentService appointmentService;
    @Autowired
    private BookingConfigService configService;
    @Autowired
    private MachineAppointmentMapper appointmentMapper;
    @Autowired
    private MachineMapper machineMapper;

    // ---------- 基本闭环：HELD 临时锁 → CONFIRMED ----------

    @Test
    void book_thenConfirm_heldToConfirmed() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(2).withMinute(0).withSecond(0).withNano(0);

        MachineAppointment appt = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        assertEquals(AppointmentStatus.HELD, appt.getStatus());
        assertEquals(18, appt.getBufferMinutes());
        assertEquals(35, appt.getLockTtlMinutes());
        assertNotNull(appt.getLockDeadline());
        // 只锁 35 分钟（容忍 1 分钟执行误差）
        long ttlMinutes = java.time.Duration.between(LocalDateTime.now(), appt.getLockDeadline()).toMinutes();
        assertTrue(ttlMinutes >= 34 && ttlMinutes <= 35, "临时锁应保留约 35 分钟，实际 " + ttlMinutes);

        MachineAppointment confirmed = appointmentService.confirm(appt.getId(),
                confirmReq(appt.getVersion(), "OP001"));
        assertEquals(AppointmentStatus.CONFIRMED, confirmed.getStatus());
        assertEquals(2, appointmentService.detail(appt.getId()).getLogs().size());
    }

    // ---------- 严格重叠 ----------

    @Test
    void overlappingWindow_rejected() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(3).withMinute(0).withSecond(0).withNano(0);
        appointmentService.book(bookReq(machineId, base, base.plusHours(1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> appointmentService.book(bookReq(machineId, base.plusMinutes(30), base.plusMinutes(90))));
        assertTrue(ex.getMessage().contains("时段重叠"));
    }

    @Test
    void differentMachine_sameWindow_bothAllowed() {
        Long m1 = newMachine();
        Long m2 = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(4).withMinute(0).withSecond(0).withNano(0);
        appointmentService.book(bookReq(m1, base, base.plusHours(1)));
        MachineAppointment second = appointmentService.book(bookReq(m2, base, base.plusHours(1)));
        assertEquals(AppointmentStatus.HELD, second.getStatus());
    }

    // ---------- 18 分钟缓冲 + 首尾相接豁免 ----------

    @Test
    void adjacentHeadToTail_allowed() {
        // 既有 10:00~11:00，新单 11:00~12:00：首尾相接不算冲突
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(5).withMinute(0).withSecond(0).withNano(0);
        appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        MachineAppointment second = appointmentService.book(bookReq(machineId, base.plusHours(1), base.plusHours(2)));
        assertEquals(AppointmentStatus.HELD, second.getStatus());
    }

    @Test
    void gapWithin18Minutes_rejected_butExact18Allowed() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(6).withMinute(0).withSecond(0).withNano(0);
        appointmentService.book(bookReq(machineId, base, base.plusHours(1)));

        // 间隔 10 分钟：缓冲不足，拒绝
        BusinessException ex = assertThrows(BusinessException.class, () -> appointmentService.book(
                bookReq(machineId, base.plusHours(1).plusMinutes(10), base.plusHours(2).plusMinutes(10))));
        assertTrue(ex.getMessage().contains("缓冲"), ex.getMessage());

        // 间隔恰好 18 分钟：放行（间隔不小于缓冲）
        MachineAppointment at18 = appointmentService.book(
                bookReq(machineId, base.plusHours(1).plusMinutes(18), base.plusHours(2).plusMinutes(18)));
        assertEquals(AppointmentStatus.HELD, at18.getStatus());
    }

    @Test
    void bufferAlsoAppliesBeforeExisting() {
        // 新单在既有单之前、仅隔 5 分钟：同样拒绝；端点相接放行
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(7).withMinute(0).withSecond(0).withNano(0);
        LocalDateTime existStart = base.plusHours(1);
        appointmentService.book(bookReq(machineId, existStart, existStart.plusHours(1)));

        BusinessException ex = assertThrows(BusinessException.class,
                () -> appointmentService.book(bookReq(machineId, base, existStart.minusMinutes(5))));
        assertTrue(ex.getMessage().contains("缓冲"));

        MachineAppointment headToTail = appointmentService.book(
                bookReq(machineId, base, existStart));
        assertEquals(AppointmentStatus.HELD, headToTail.getStatus());
    }

    // ---------- 35 分钟临时锁：过期自动释放 ----------

    @Test
    void expiredHeldLock_autoSwept_andWindowReopens() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(8).withMinute(0).withSecond(0).withNano(0);
        MachineAppointment appt = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));

        // 把锁截止时间拨到过去（模拟超过 35 分钟未确认）
        forceDeadlinePast(appt.getId());

        int released = appointmentService.sweepExpiredLocks();
        assertTrue(released >= 1);
        MachineAppointment reloaded = appointmentMapper.selectById(appt.getId());
        assertEquals(AppointmentStatus.EXPIRED, reloaded.getStatus());
        assertEquals(2, appointmentService.detail(appt.getId()).getLogs().size());

        // 时段已释放：可立即再约同一窗口（首尾相接/占用已消失）
        MachineAppointment rebook = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        assertEquals(AppointmentStatus.HELD, rebook.getStatus());
    }

    @Test
    void confirmAfterLockDeadline_rejectedAndAutoReleased() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(9).withMinute(0).withSecond(0).withNano(0);
        MachineAppointment appt = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        forceDeadlinePast(appt.getId());

        assertThrows(BusinessException.class,
                () -> appointmentService.confirm(appt.getId(), confirmReq(appt.getVersion(), "OP001")));
        assertEquals(AppointmentStatus.EXPIRED, appointmentMapper.selectById(appt.getId()).getStatus());
    }

    @Test
    void expiredHeldLock_lazilyReleasedOnNextBook() {
        // 不跑定时扫描，新增预约遇到该机已超时的 HELD 也会先惰性释放
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(10).withMinute(0).withSecond(0).withNano(0);
        MachineAppointment stale = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        forceDeadlinePast(stale.getId());

        MachineAppointment rebook = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        assertEquals(AppointmentStatus.HELD, rebook.getStatus());
        assertEquals(AppointmentStatus.EXPIRED, appointmentMapper.selectById(stale.getId()).getStatus());
    }

    // ---------- 取消立即释放可再约 ----------

    @Test
    void cancel_releasesImmediately_canRebook() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(11).withMinute(0).withSecond(0).withNano(0);
        MachineAppointment appt = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        appointmentService.confirm(appt.getId(), confirmReq(appt.getVersion(), "OP001"));

        MachineAppointment confirmed = appointmentMapper.selectById(appt.getId());
        MachineAppointment cancelled = appointmentService.cancel(confirmed.getId(),
                cancelReq(confirmed.getVersion(), "OP001", "天气原因"));
        assertEquals(AppointmentStatus.CANCELLED, cancelled.getStatus());

        // 立即再约同一时段，成功
        MachineAppointment rebook = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        assertEquals(AppointmentStatus.HELD, rebook.getStatus());
        assertFalse(rebook.getId().equals(appt.getId()));
    }

    @Test
    void cancel_heldWithoutConfirm_alsoReleases() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(12).withMinute(0).withSecond(0).withNano(0);
        MachineAppointment appt = appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        appointmentService.cancel(appt.getId(), cancelReq(appt.getVersion(), "OP001", "改期"));

        MachineAppointment rebook = appointmentService.book(
                bookReq(machineId, base.plusMinutes(5), base.plusMinutes(50)));
        assertEquals(AppointmentStatus.HELD, rebook.getStatus());
    }

    // ---------- 预案：一键切回旧口径，仅拦严格重叠 ----------

    @Test
    void legacyMode_allowsTightGap_butStillBlocksOverlap() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(13).withMinute(0).withSecond(0).withNano(0);
        appointmentService.book(bookReq(machineId, base, base.plusHours(1)));

        // 切回旧逻辑
        assertEquals(BookingMode.LEGACY,
                configService.updateMode(BookingMode.LEGACY, "REQ-MODE-LEG-" + SEQ.incrementAndGet()));
        try {
            // 旧口径：间隔仅 10 分钟也放行（不看缓冲）
            MachineAppointment tight = appointmentService.book(
                    bookReq(machineId, base.plusHours(1).plusMinutes(10), base.plusHours(2)));
            assertEquals(AppointmentStatus.HELD, tight.getStatus());

            // 严格重叠仍然拦截
            assertThrows(BusinessException.class, () -> appointmentService.book(
                    bookReq(machineId, base.plusMinutes(20), base.plusMinutes(40))));
        } finally {
            // 恢复新口径
            configService.updateMode(BookingMode.NEW, "REQ-MODE-NEW-" + SEQ.incrementAndGet());
        }
        assertEquals(BookingMode.NEW, configService.getMode());
    }

    @Test
    void parallelMode_stillEnforcesNewBuffer() {
        Long machineId = newMachine();
        LocalDateTime base = LocalDateTime.now().plusDays(14).withMinute(0).withSecond(0).withNano(0);
        appointmentService.book(bookReq(machineId, base, base.plusHours(1)));
        configService.updateMode(BookingMode.PARALLEL, "REQ-MODE-PAR-" + SEQ.incrementAndGet());
        try {
            assertThrows(BusinessException.class, () -> appointmentService.book(
                    bookReq(machineId, base.plusHours(1).plusMinutes(3), base.plusHours(2))));
        } finally {
            configService.updateMode(BookingMode.NEW, "REQ-MODE-NEW2-" + SEQ.incrementAndGet());
        }
    }

    @Test
    void pastWindow_rejected() {
        Long machineId = newMachine();
        LocalDateTime past = LocalDateTime.now().minusHours(2);
        assertThrows(BusinessException.class,
                () -> appointmentService.book(bookReq(machineId, past, past.plusMinutes(30))));
    }

    // ---------- helpers ----------

    private void forceDeadlinePast(Long id) {
        appointmentMapper.update(null, new LambdaUpdateWrapper<MachineAppointment>()
                .eq(MachineAppointment::getId, id)
                .set(MachineAppointment::getLockDeadline, LocalDateTime.now().minusMinutes(1)));
    }

    private Long newMachine() {
        Machine machine = new Machine();
        machine.setMachineCode("M-BOOK-" + SEQ.incrementAndGet());
        machine.setMachineName("排班测试农机");
        machine.setStatus("ACTIVE");
        machineMapper.insert(machine);
        return machine.getId();
    }

    private AppointmentBookRequest bookReq(Long machineId, LocalDateTime start, LocalDateTime end) {
        AppointmentBookRequest req = new AppointmentBookRequest();
        req.setMachineId(machineId);
        req.setOperatorCode("OP001");
        req.setPlotName("测试地块");
        req.setStartTime(start);
        req.setEndTime(end);
        req.setRequestNo("REQ-BOOK-" + SEQ.incrementAndGet());
        return req;
    }

    private AppointmentConfirmRequest confirmReq(int version, String operator) {
        AppointmentConfirmRequest req = new AppointmentConfirmRequest();
        req.setVersion(version);
        req.setOperator(operator);
        req.setRequestNo("REQ-CONF-" + SEQ.incrementAndGet());
        return req;
    }

    private AppointmentCancelRequest cancelReq(int version, String operator, String reason) {
        AppointmentCancelRequest req = new AppointmentCancelRequest();
        req.setVersion(version);
        req.setOperator(operator);
        req.setCancelReason(reason);
        req.setRequestNo("REQ-CANCEL-" + SEQ.incrementAndGet());
        return req;
    }
}
