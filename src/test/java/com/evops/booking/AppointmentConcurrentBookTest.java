package com.evops.booking;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.booking.dto.AppointmentBookRequest;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 同一农机同一时段并发两笔预约：只能成功一条（JVM 分段锁串行 + 严格重叠判定）。
 */
@SpringBootTest
@ActiveProfiles("test")
class AppointmentConcurrentBookTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private AppointmentService appointmentService;
    @Autowired
    private MachineAppointmentMapper appointmentMapper;
    @Autowired
    private MachineMapper machineMapper;

    @Test
    void sameWindow_concurrentBook_onlyOneSucceeds() throws Exception {
        Machine machine = new Machine();
        machine.setMachineCode("M-BOOK-CONC-" + SEQ.incrementAndGet());
        machine.setMachineName("并发预约测试农机");
        machine.setStatus("ACTIVE");
        machineMapper.insert(machine);

        LocalDateTime base = LocalDateTime.now().plusDays(20).withMinute(0).withSecond(0).withNano(0);

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        Runnable r1 = () -> runBook(machine.getId(), base, base.plusHours(1),
                "REQ-CONC-" + SEQ.incrementAndGet(), ready, start, success, rejected);
        Runnable r2 = () -> runBook(machine.getId(), base.plusMinutes(20), base.plusMinutes(50),
                "REQ-CONC-" + SEQ.incrementAndGet(), ready, start, success, rejected);
        Thread t1 = new Thread(r1);
        Thread t2 = new Thread(r2);
        t1.start();
        t2.start();
        ready.await();
        start.countDown();
        t1.join(10_000);
        t2.join(10_000);

        assertEquals(1, success.get(), "同一时段并发预约必须只有一笔成功");
        assertEquals(1, rejected.get(), "另一笔必须被防撞拦截");

        Long heldCount = appointmentMapper.selectCount(new LambdaQueryWrapper<MachineAppointment>()
                .eq(MachineAppointment::getMachineId, machine.getId())
                .eq(MachineAppointment::getStatus, AppointmentStatus.HELD));
        assertEquals(1L, heldCount, "该时段只能有一笔占位预约");
    }

    private void runBook(Long machineId, LocalDateTime start, LocalDateTime end, String requestNo,
                         CountDownLatch ready, CountDownLatch gate,
                         AtomicInteger success, AtomicInteger rejected) {
        try {
            ready.countDown();
            gate.await();
            AppointmentBookRequest req = new AppointmentBookRequest();
            req.setMachineId(machineId);
            req.setOperatorCode("OP001");
            req.setStartTime(start);
            req.setEndTime(end);
            req.setRequestNo(requestNo);
            appointmentService.book(req);
            success.incrementAndGet();
        } catch (BusinessException e) {
            rejected.incrementAndGet();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
