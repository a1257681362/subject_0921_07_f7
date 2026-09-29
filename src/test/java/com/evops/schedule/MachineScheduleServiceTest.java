package com.evops.schedule;

import com.evops.common.BusinessException;
import com.evops.machine.entity.Machine;
import com.evops.machine.mapper.MachineMapper;
import com.evops.schedule.dto.ScheduleCancelRequest;
import com.evops.schedule.dto.ScheduleConfirmRequest;
import com.evops.schedule.dto.ScheduleCreateRequest;
import com.evops.schedule.entity.MachineSchedule;
import com.evops.schedule.mapper.MachineScheduleMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 农机排班防撞主链路：重叠拦截、首尾相接允许、18 分钟缓冲边界、
 * 待确认 35 分钟临时锁与过期自动释放、取消立即释放再约、LEGACY 旧逻辑回退、并发防撞。
 */
@SpringBootTest
@ActiveProfiles("test")
class MachineScheduleServiceTest {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MachineScheduleService scheduleService;
    @Autowired
    private ScheduleConfigService configService;
    @Autowired
    private MachineScheduleMapper scheduleMapper;
    @Autowired
    private MachineMapper machineMapper;

    // ---- 防撞判定 ----

    @Test
    void overlappingAppointments_rejected() {
        Machine m = newMachine();
        book(m.getId(), "10:00", "11:00");

        // 真重叠（中段交叉 / 完全相同 / 被包住）一律拒绝
        assertClash(m.getId(), "10:30", "11:30");
        assertClash(m.getId(), "10:00", "11:00");
        assertClash(m.getId(), "09:00", "12:00");
        // 不同农机同一时段不冲突
        Machine other = newMachine();
        MachineSchedule ok = book(other.getId(), "10:00", "11:00");
        assertEquals(ScheduleConstants.STATUS_PENDING, ok.getStatus());
    }

    @Test
    void backToBack_isAllowed_butBufferGap_isNot() {
        Machine m = newMachine();
        book(m.getId(), "10:00", "11:00");

        // 首尾相接（间隔 0）允许：后接与前贴
        assertEquals(ScheduleConstants.STATUS_PENDING, book(m.getId(), "11:00", "12:00").getStatus());
        // 前贴：09:00-10:00 与 10:00-11:00 首尾相接
        assertEquals(ScheduleConstants.STATUS_PENDING, book(m.getId(), "09:00", "10:00").getStatus());

        // 缓冲带内（0 < gap < 18）拒绝
        assertClash(m.getId(), "12:10", "13:00");
        assertClash(m.getId(), "12:17", "13:00");
        assertClash(m.getId(), "08:50", "08:59");
        // 恰好 18 分钟允许
        assertEquals(ScheduleConstants.STATUS_PENDING, book(m.getId(), "12:18", "13:00").getStatus());
    }

    @Test
    void endNotAfterStart_rejected() {
        Machine m = newMachine();
        BusinessException ex = assertThrows(BusinessException.class,
                () -> book(m.getId(), "11:00", "11:00"));
        assertTrue(ex.getMessage().contains("结束时间"));
    }

    // ---- 35 分钟临时锁 ----

    @Test
    void pendingHoldsLockFor35Minutes_andConfirmReleasesIt() {
        Machine m = newMachine();
        MachineSchedule s = book(m.getId(), "14:00", "15:00");
        assertEquals(ScheduleConstants.STATUS_PENDING, s.getStatus());
        assertNotNull(s.getLockUntil());
        long lockMinutes = Duration.between(s.getCreateTime(), s.getLockUntil()).toMinutes();
        assertTrue(lockMinutes >= 34 && lockMinutes <= 35, "待确认预约只锁 35 分钟（含毫秒取整），实际：" + lockMinutes);

        // 未确认、未过期时仍占位，重叠约不进
        assertClash(m.getId(), "14:30", "14:45");

        MachineSchedule confirmed = scheduleService.confirm(s.getId(), confirmReq());
        assertEquals(ScheduleConstants.STATUS_CONFIRMED, confirmed.getStatus());
        assertNull(confirmed.getLockUntil(), "确认后解除临时锁，转长期占位");
        assertNotNull(confirmed.getConfirmTime());
    }

    @Test
    void expiredPending_isAutoReleased_andSlotRebookable() {
        Machine m = newMachine();
        MachineSchedule s = book(m.getId(), "16:00", "17:00");
        // 把临时锁改到过去，模拟 35 分钟已过仍未确认
        expireLockInDb(s.getId());

        // 定时/手工全局释放
        int released = scheduleService.releaseExpired();
        assertTrue(released >= 1);
        MachineSchedule reloaded = scheduleMapper.selectById(s.getId());
        assertEquals(ScheduleConstants.STATUS_EXPIRED, reloaded.getStatus());
        assertNotNull(reloaded.getExpireTime());

        // 释放后原时段可再约
        MachineSchedule again = book(m.getId(), "16:00", "17:00");
        assertEquals(ScheduleConstants.STATUS_PENDING, again.getStatus());
    }

    @Test
    void lazyExpire_whenCreating_overlappingExpiredLock() {
        Machine m = newMachine();
        MachineSchedule s = book(m.getId(), "18:00", "19:00");
        expireLockInDb(s.getId());

        // 不显式跑释放任务：新建重叠预约时先按机惰性放掉过期锁，新约成功
        MachineSchedule again = book(m.getId(), "18:00", "19:00");
        assertEquals(ScheduleConstants.STATUS_PENDING, again.getStatus());
        assertEquals(ScheduleConstants.STATUS_EXPIRED, scheduleMapper.selectById(s.getId()).getStatus());
    }

    @Test
    void futureLockAndConfirmed_areNotReleased() {
        Machine m = newMachine();
        MachineSchedule pending = book(m.getId(), "20:00", "20:30");      // lock_until 在未来
        MachineSchedule willConfirm = book(m.getId(), "21:00", "21:30");
        scheduleService.confirm(willConfirm.getId(), confirmReq());

        scheduleService.releaseExpired();
        assertEquals(ScheduleConstants.STATUS_PENDING, scheduleMapper.selectById(pending.getId()).getStatus(),
                "未到 35 分钟的待确认占位不应释放");
        assertEquals(ScheduleConstants.STATUS_CONFIRMED,
                scheduleMapper.selectById(willConfirm.getId()).getStatus(), "已确认预约不受过期释放影响");
    }

    @Test
    void expiredOrCancelled_cannotConfirm() {
        Machine m = newMachine();
        MachineSchedule s = book(m.getId(), "07:00", "08:00");
        expireLockInDb(s.getId());
        // 确认前先惰性释放 → 已过期，不能确认
        BusinessException ex = assertThrows(BusinessException.class,
                () -> scheduleService.confirm(s.getId(), confirmReq()));
        assertTrue(ex.getMessage().contains("EXPIRED"));
    }

    // ---- 取消立即释放 ----

    @Test
    void cancel_releasesImmediately_andCanRebook() {
        Machine m = newMachine();
        MachineSchedule s = book(m.getId(), "09:00", "10:00");
        scheduleService.cancel(s.getId(), cancelReq("临时有事"));
        assertEquals(ScheduleConstants.STATUS_CANCELLED, scheduleMapper.selectById(s.getId()).getStatus());

        // 立即可以再约同一时段（不需要等过期）
        MachineSchedule again = book(m.getId(), "09:00", "10:00");
        assertEquals(ScheduleConstants.STATUS_PENDING, again.getStatus());

        // 重复取消报错
        assertThrows(BusinessException.class,
                () -> scheduleService.cancel(s.getId(), cancelReq("再取消")));
    }

    @Test
    void cancelConfirmed_releasesSlot() {
        Machine m = newMachine();
        MachineSchedule s = book(m.getId(), "11:00", "11:30");
        scheduleService.confirm(s.getId(), confirmReq());
        scheduleService.cancel(s.getId(), cancelReq("完工取消"));
        assertEquals(ScheduleConstants.STATUS_PENDING, book(m.getId(), "11:00", "11:30").getStatus());
    }

    // ---- 回退旧逻辑 ----

    @Test
    void legacyMode_noClashCheck_registerConfirmed_thenSwitchBack() {
        Machine m = newMachine();
        configService.updateEngineMode(ScheduleConstants.MODE_LEGACY, uniq("REQ-MODE-"));
        try {
            MachineSchedule a = book(m.getId(), "10:00", "11:00");
            MachineSchedule b = book(m.getId(), "10:30", "11:30"); // 旧逻辑不拦截
            assertEquals(ScheduleConstants.STATUS_CONFIRMED, a.getStatus());
            assertEquals(ScheduleConstants.STATUS_CONFIRMED, b.getStatus());
            assertEquals(ScheduleConstants.SOURCE_LEGACY, a.getSource());
            assertNull(a.getLockUntil());
        } finally {
            configService.updateEngineMode(ScheduleConstants.MODE_NEW, uniq("REQ-MODE-"));
        }
        // 切回新逻辑后重叠再次被拦
        assertClash(m.getId(), "10:15", "10:45");
    }

    // ---- 并发 ----

    @Test
    void sameMachine_concurrentOverlap_onlyOneSucceeds() throws Exception {
        Machine m = newMachine();
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        Runnable r = () -> {
            try {
                ready.countDown();
                start.await();
                book(m.getId(), "13:00", "14:00");
                success.incrementAndGet();
            } catch (BusinessException e) {
                rejected.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        };
        Thread t1 = new Thread(r);
        Thread t2 = new Thread(r);
        t1.start();
        t2.start();
        ready.await();
        start.countDown();
        t1.join(10_000);
        t2.join(10_000);

        assertEquals(1, success.get(), "同一农机重叠时段并发预约只能成功一笔");
        assertEquals(1, rejected.get(), "另一笔必须被防撞拦下");
    }

    @Test
    void duplicateScheduleNoAndRequestNo_rejected() {
        Machine m = newMachine();
        String no = "SCH-DUP-" + SEQ.incrementAndGet();
        String reqNo = uniq("REQ-DUP-");
        scheduleService.create(req(no, m.getId(), "10:00", "11:00", reqNo));
        assertThrows(BusinessException.class,
                () -> scheduleService.create(req(no, m.getId(), "15:00", "16:00", uniq("REQ-OTHER-"))));
        // 同请求号重放
        assertThrows(BusinessException.class,
                () -> scheduleService.create(req(uniq("SCH-OTHER-"), m.getId(), "16:00", "17:00", reqNo)));
    }

    @Test
    void disabledMachine_cannotSchedule() {
        Machine m = newMachine();
        m.setStatus("DISABLED");
        machineMapper.updateById(m);
        assertThrows(BusinessException.class, () -> book(m.getId(), "10:00", "11:00"));
    }

    // ---- helpers ----

    private Machine newMachine() {
        Machine m = new Machine();
        m.setMachineCode("M-SCH-" + SEQ.incrementAndGet());
        m.setMachineName("排班测试机");
        m.setStatus("ACTIVE");
        machineMapper.insert(m);
        return m;
    }

    private MachineSchedule book(Long machineId, String start, String end) {
        return scheduleService.create(req(uniq("SCH-"), machineId, start, end, uniq("REQ-")));
    }

    private void assertClash(Long machineId, String start, String end) {
        BusinessException ex = assertThrows(BusinessException.class, () -> book(machineId, start, end));
        assertTrue(ex.getMessage().contains("预约") || ex.getMessage().contains("缓冲"),
                "应报重叠/缓冲冲突，实际：" + ex.getMessage());
    }

    private ScheduleCreateRequest req(String no, Long machineId, String start, String end, String requestNo) {
        LocalDateTime base = LocalDateTime.of(2030, 6, 1, 0, 0);
        ScheduleCreateRequest r = new ScheduleCreateRequest();
        r.setScheduleNo(no);
        r.setMachineId(machineId);
        r.setStartTime(base.withHour(Integer.parseInt(start.substring(0, 2)))
                .withMinute(Integer.parseInt(start.substring(3))).format(FMT));
        r.setEndTime(base.withHour(Integer.parseInt(end.substring(0, 2)))
                .withMinute(Integer.parseInt(end.substring(3))).format(FMT));
        r.setRequestNo(requestNo);
        return r;
    }

    private ScheduleConfirmRequest confirmReq() {
        ScheduleConfirmRequest r = new ScheduleConfirmRequest();
        r.setRequestNo(uniq("REQ-CONF-"));
        return r;
    }

    private ScheduleCancelRequest cancelReq(String reason) {
        ScheduleCancelRequest r = new ScheduleCancelRequest();
        r.setRequestNo(uniq("REQ-CANCEL-"));
        r.setCancelReason(reason);
        return r;
    }

    private void expireLockInDb(Long id) {
        MachineSchedule s = scheduleMapper.selectById(id);
        s.setLockUntil(LocalDateTime.now().minusMinutes(1));
        scheduleMapper.updateById(s);
    }

    private String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }
}
