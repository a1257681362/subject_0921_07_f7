package com.evops.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 排班防撞接口层：统一返回、必填校验、新建→确认/取消、缓冲拦截、模式切换回退闭环。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MachineScheduleApiTest {

    @Autowired
    private MockMvc mockMvc;

    private long nano() {
        return System.nanoTime();
    }

    private String createMachine() throws Exception {
        String body = "{\"machineCode\":\"M-SCHA-" + nano() + "\",\"machineName\":\"接口排班机\","
                + "\"requestNo\":\"REQ-SCHA-M-" + nano() + "\"}";
        String resp = mockMvc.perform(post("/api/machines")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return com.jayway.jsonpath.JsonPath.read(resp, "$.data.id").toString();
    }

    private String createSchedule(String machineId, String no, String start, String end, String reqNo) throws Exception {
        String body = "{\"scheduleNo\":\"" + no + "\",\"machineId\":" + machineId
                + ",\"startTime\":\"2031-05-01 " + start + "\",\"endTime\":\"2031-05-01 " + end + "\""
                + ",\"requestNo\":\"" + reqNo + "\"}";
        return mockMvc.perform(post("/api/machine-schedules")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void create_missingFields_returnsUnifiedFail() throws Exception {
        mockMvc.perform(post("/api/machine-schedules")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("不能为空")));
    }

    @Test
    void createConfirmQueryClosedLoop() throws Exception {
        String machineId = createMachine();
        String no = "SCH-API-" + nano();
        String resp = createSchedule(machineId, no, "09:00:00", "10:00:00", "REQ-SCHA-W-" + nano());
        com.jayway.jsonpath.ReadContext ctx = com.jayway.jsonpath.JsonPath.parse(resp);
        assertEquals(true, ctx.read("$.success"));
        Number id = ctx.read("$.data.id");
        org.junit.jupiter.api.Assertions.assertEquals("PENDING", ctx.read("$.data.status"));

        // 重叠预约被拦
        String clash = "{\"scheduleNo\":\"SCH-API-C-" + nano() + "\",\"machineId\":" + machineId
                + ",\"startTime\":\"2031-05-01 09:30:00\",\"endTime\":\"2031-05-01 10:30:00\""
                + ",\"requestNo\":\"REQ-SCHA-C-" + nano() + "\"}";
        mockMvc.perform(post("/api/machine-schedules").contentType(MediaType.APPLICATION_JSON).content(clash))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("重叠")));

        // 确认
        String confirm = "{\"requestNo\":\"REQ-SCHA-CF-" + nano() + "\"}";
        mockMvc.perform(post("/api/machine-schedules/" + id + "/confirm")
                        .contentType(MediaType.APPLICATION_JSON).content(confirm))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"));

        // 列表按农机过滤
        mockMvc.perform(get("/api/machine-schedules").param("machineId", machineId))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data[0].scheduleNo").value(no));
    }

    @Test
    void cancelThenRebook_andModeSwitch() throws Exception {
        String machineId = createMachine();
        String resp = createSchedule(machineId, "SCH-API-X-" + nano(), "14:00:00", "15:00:00",
                "REQ-SCHA-X-" + nano());
        Number id = com.jayway.jsonpath.JsonPath.read(resp, "$.data.id");

        mockMvc.perform(post("/api/machine-schedules/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"requestNo\":\"REQ-SCHA-XC-" + nano() + "\",\"cancelReason\":\"test\"}"))
                .andExpect(jsonPath("$.data.status").value("CANCELLED"));

        // 取消后同时段可再约
        createSchedule(machineId, "SCH-API-R-" + nano(), "14:00:00", "15:00:00", "REQ-SCHA-R-" + nano());

        // 切到 LEGACY：重叠也放行且直接 CONFIRMED
        String legacy = "{\"engineMode\":\"LEGACY\",\"requestNo\":\"REQ-SCHA-MD-" + nano() + "\"}";
        mockMvc.perform(post("/api/machine-schedules/config/engine-mode")
                        .contentType(MediaType.APPLICATION_JSON).content(legacy))
                .andExpect(jsonPath("$.data.engineMode").value("LEGACY"));
        try {
            String resp2 = createSchedule(machineId, "SCH-API-L-" + nano(), "14:30:00", "15:30:00",
                    "REQ-SCHA-L-" + nano());
            org.junit.jupiter.api.Assertions.assertEquals("CONFIRMED",
                    com.jayway.jsonpath.JsonPath.read(resp2, "$.data.status"));
        } finally {
            // 复位为 NEW，避免影响其它用例
            String back = "{\"engineMode\":\"NEW\",\"requestNo\":\"REQ-SCHA-MB-" + nano() + "\"}";
            mockMvc.perform(post("/api/machine-schedules/config/engine-mode")
                    .contentType(MediaType.APPLICATION_JSON).content(back));
        }
    }

    private static void assertEquals(Object expected, Object actual) {
        org.junit.jupiter.api.Assertions.assertEquals(expected, actual);
    }
}
