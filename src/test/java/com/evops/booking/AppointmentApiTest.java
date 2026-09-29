package com.evops.booking;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 预约接口层：统一返回结构、必填校验、预约→确认→详情闭环、冲突/回退。独立内存库隔离。 */
@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:evops_appointment_api_test;DB_CLOSE_DELAY=-1")
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AppointmentApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();
    private static final DateTimeFormatter ISO = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

    @Autowired
    private MockMvc mockMvc;

    @Test
    void book_missingFields_returnsUnifiedFail() throws Exception {
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("不能为空")));
    }

    @Test
    void bookConfirmCancelDetail_closedLoop() throws Exception {
        Long machineId = createMachine();
        LocalDateTime start = LocalDateTime.now().plusDays(2).withSecond(0).withNano(0);
        LocalDateTime end = start.plusHours(1);

        // 预约 -> HELD
        String bookBody = "{\"machineId\":" + machineId
                + ",\"operatorCode\":\"OP001\",\"plotName\":\"东地块\","
                + "\"startTime\":\"" + ISO.format(start) + "\",\"endTime\":\"" + ISO.format(end) + "\","
                + "\"requestNo\":\"" + uniq("REQ-API-BOOK-") + "\"}";
        String bookResp = mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(bookBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("HELD"))
                .andExpect(jsonPath("$.data.bufferMinutes").value(18))
                .andExpect(jsonPath("$.data.lockTtlMinutes").value(35))
                .andReturn().getResponse().getContentAsString();
        Long id = readId(bookResp);
        int version = 0;

        // 缓冲冲突：仅隔 10 分钟的第二单被拒
        LocalDateTime s2 = end.plusMinutes(10);
        String conflictBody = "{\"machineId\":" + machineId
                + ",\"operatorCode\":\"OP002\",\"startTime\":\"" + ISO.format(s2)
                + "\",\"endTime\":\"" + ISO.format(s2.plusHours(1)) + "\","
                + "\"requestNo\":\"" + uniq("REQ-API-BOOK-") + "\"}";
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(conflictBody))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(containsString("缓冲")));

        // 确认 -> CONFIRMED
        mockMvc.perform(post("/api/appointments/" + id + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":" + version + ",\"operator\":\"OP001\","
                                + "\"requestNo\":\"" + uniq("REQ-API-CONF-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // 详情：单头 + 两条日志
        mockMvc.perform(get("/api/appointments/" + id))
                .andExpect(jsonPath("$.data.appointment.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.logs.length()").value(2));

        // 取消（需版本 1）-> CANCELLED
        mockMvc.perform(post("/api/appointments/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\":1,\"operator\":\"OP001\",\"cancelReason\":\"下雨\","
                                + "\"requestNo\":\"" + uniq("REQ-API-CANCEL-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 取消后原时段立即可再约
        mockMvc.perform(post("/api/appointments")
                        .contentType(MediaType.APPLICATION_JSON).content(bookBody.replaceFirst(
                                "REQ-API-BOOK-\\d+", uniq("REQ-API-REBOOK-"))))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("HELD"));

        // 列表过滤
        mockMvc.perform(get("/api/appointments").param("machineId", String.valueOf(machineId)))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2));
    }

    @Test
    void configMode_cutoverToLegacyAndBack() throws Exception {
        mockMvc.perform(get("/api/appointments/config"))
                .andExpect(jsonPath("$.data.mode").value("NEW"))
                .andExpect(jsonPath("$.data.bufferMinutes").value(18))
                .andExpect(jsonPath("$.data.lockTtlMinutes").value(35));

        mockMvc.perform(post("/api/appointments/config/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"LEGACY\",\"requestNo\":\"" + uniq("REQ-API-MODE-") + "\"}"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").value("LEGACY"));
        mockMvc.perform(get("/api/appointments/config"))
                .andExpect(jsonPath("$.data.mode").value("LEGACY"));

        // 非法模式拒绝
        mockMvc.perform(post("/api/appointments/config/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"BOGUS\",\"requestNo\":\"" + uniq("REQ-API-MODE-") + "\"}"))
                .andExpect(jsonPath("$.success").value(false));

        mockMvc.perform(post("/api/appointments/config/mode")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"NEW\",\"requestNo\":\"" + uniq("REQ-API-MODE-") + "\"}"))
                .andExpect(jsonPath("$.data").value("NEW"));
    }

    private Long createMachine() throws Exception {
        String code = "M-API-" + SEQ.incrementAndGet();
        String resp = mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"machineCode\":\"" + code + "\",\"machineName\":\"接口测试农机\","
                                + "\"requestNo\":\"" + uniq("REQ-API-M-") + "\"}"))
                .andReturn().getResponse().getContentAsString();
        return readId(resp);
    }

    /** 极简读取 data.id，避免引入额外依赖。 */
    private Long readId(String json) {
        int key = json.indexOf("\"id\":");
        int comma = json.indexOf(',', key);
        return Long.parseLong(json.substring(key + 5, comma).trim());
    }

    private String uniq(String prefix) {
        return prefix + SEQ.incrementAndGet();
    }
}
