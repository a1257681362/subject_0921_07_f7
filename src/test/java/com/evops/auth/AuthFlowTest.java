package com.evops.auth;

import com.evops.auth.entity.RefreshToken;
import com.evops.auth.entity.TokenBlacklistEntry;
import com.evops.auth.mapper.RefreshTokenMapper;
import com.evops.auth.mapper.TokenBlacklistMapper;
import com.evops.auth.service.BlacklistService;
import com.evops.auth.service.RefreshTokenService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * F6 认证全链路：登录双令牌、过滤链 401、刷新一次性轮换（含并发重放）、
 * 登出黑名单、黑名单/刷新台账过期自动清理。本类单独打开 auth.enabled=true。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(properties = "auth.enabled=true")
class AuthFlowTest {

    private static final String USERNAME = "bootstrap";
    private static final String PASSWORD = "bootstrap123";

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private RefreshTokenMapper refreshTokenMapper;
    @Autowired
    private TokenBlacklistMapper blacklistMapper;
    @Autowired
    private BlacklistService blacklistService;
    @Autowired
    private RefreshTokenService refreshTokenService;

    @Test
    void login_returnsTokenPair_withoutSensitivePlaintext() throws Exception {
        JsonNode pair = login();
        assertTrue(pair.path("accessToken").asText().length() > 20);
        assertTrue(pair.path("refreshToken").asText().length() > 20);
        assertEquals("Bearer", pair.path("tokenType").asText());
        assertTrue(pair.has("accessExpiresAt"));
        assertTrue(pair.has("refreshExpiresAt"));
        // 令牌中不带密码等敏感明文
        assertFalse(pair.toString().contains(PASSWORD));
        assertFalse(pair.path("accessToken").asText().contains(PASSWORD));
    }

    @Test
    void wrongPassword_rejected() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + USERNAME + "\",\"password\":\"wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("用户名或密码不正确")));
    }

    @Test
    void businessApi_withoutToken_401() throws Exception {
        mockMvc.perform(get("/api/machines"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("登录令牌")));
    }

    @Test
    void businessApi_withAccessToken_ok() throws Exception {
        String access = login().path("accessToken").asText();
        mockMvc.perform(get("/api/machines").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void refreshToken_cannotAccessBusiness() throws Exception {
        String refresh = login().path("refreshToken").asText();
        mockMvc.perform(get("/api/machines").header("Authorization", "Bearer " + refresh))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("令牌类型错误")));
    }

    @Test
    void tamperedToken_rejected() throws Exception {
        String access = login().path("accessToken").asText();
        String tampered = access.substring(0, access.length() - 4) + "XXXX";
        mockMvc.perform(get("/api/machines").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("令牌无效")));
    }

    @Test
    void refresh_rotatesPair_andOldRefreshIsOneTimeOnly() throws Exception {
        JsonNode first = login();
        String oldRefresh = first.path("refreshToken").asText();

        JsonNode second = rotate(oldRefresh);
        String newAccess = second.path("accessToken").asText();
        String newRefresh = second.path("refreshToken").asText();
        assertFalse(newRefresh.equals(oldRefresh));

        // 新一对可用
        mockMvc.perform(get("/api/machines").header("Authorization", "Bearer " + newAccess))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // 旧刷新令牌第二次使用：一次性，拒绝
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + oldRefresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("刷新令牌已使用")));

        // 重放疑似泄露：沿换新链把最新有效的新刷新令牌也吊销
        RefreshToken latest = refreshTokenMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getJti, jwtJti(newRefresh)));
        assertEquals(RefreshToken.STATUS_REVOKED, latest.getStatus());

        // 旧刷新令牌台账状态为 USED 且记录了换新去向
        RefreshToken used = refreshTokenMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getJti, jwtJti(oldRefresh)));
        assertEquals(RefreshToken.STATUS_USED, used.getStatus());
        assertEquals(jwtJti(newRefresh), used.getReplacedByJti());
    }

    @Test
    void replayOldToken_revokesWholeChain() throws Exception {
        // r1 换新得 r2，r2 再换新得 r3（r3 为当前唯一 ACTIVE）
        String r1 = login().path("refreshToken").asText();
        String r2 = rotate(r1).path("refreshToken").asText();
        String r3 = rotate(r2).path("refreshToken").asText();
        assertEquals(RefreshToken.STATUS_ACTIVE,
                refreshTokenMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                        .LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getJti, jwtJti(r3))).getStatus());

        // 窃取者拿最早的 r1 重放 → 拒绝，且 r3 沿链被连锁吊销
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + r1 + "\"}"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("刷新令牌已使用")));

        assertEquals(RefreshToken.STATUS_REVOKED,
                refreshTokenMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                        .LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getJti, jwtJti(r3))).getStatus());
    }

    @Test
    void concurrentRefresh_onlyOneSucceeds() throws Exception {
        String refresh = login().path("refreshToken").asText();
        int threads = 4;
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger success = new AtomicInteger();
        AtomicInteger failure = new AtomicInteger();
        for (int i = 0; i < threads; i++) {
            new Thread(() -> {
                try {
                    start.await();
                    String resp = mockMvc.perform(post("/api/auth/refresh")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content("{\"refreshToken\":\"" + refresh + "\"}"))
                            .andReturn().getResponse().getContentAsString();
                    if (objectMapper.readTree(resp).path("success").asBoolean()) {
                        success.incrementAndGet();
                    } else {
                        failure.incrementAndGet();
                    }
                } catch (Exception ignored) {
                    failure.incrementAndGet();
                } finally {
                    done.countDown();
                }
            }).start();
        }
        start.countDown();
        assertTrue(done.await(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, success.get(), "并发刷新只允许一笔成功（一次性）");
        assertEquals(threads - 1, failure.get());
    }

    @Test
    void logout_blacklistsAccessAndRevokesRefresh() throws Exception {
        JsonNode pair = login();
        String access = pair.path("accessToken").asText();
        String refresh = pair.path("refreshToken").asText();

        // 登出前可用
        mockMvc.perform(get("/api/machines").header("Authorization", "Bearer " + access))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/logout")
                        .header("Authorization", "Bearer " + access)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        // access 进黑名单
        mockMvc.perform(get("/api/machines").header("Authorization", "Bearer " + access))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已登出")));

        // refresh 已吊销：换新被拒，提示重新登录
        mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refresh + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("已登出")));

        assertEquals(RefreshToken.STATUS_REVOKED,
                refreshTokenMapper.selectOne(new com.baomidou.mybatisplus.core.conditions.query
                        .LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getJti, jwtJti(refresh)))
                        .getStatus());
        assertTrue(blacklistService.isBlacklisted(jwtJti(access)));
    }

    @Test
    void expiredBlacklistEntries_arePurged() {
        TokenBlacklistEntry expired = new TokenBlacklistEntry();
        expired.setJti("test-bl-" + System.nanoTime());
        expired.setUsername(USERNAME);
        expired.setTokenType("ACCESS");
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        expired.setReason(TokenBlacklistEntry.REASON_LOGOUT);
        blacklistMapper.insert(expired);
        assertTrue(blacklistService.isBlacklisted(expired.getJti()));

        blacklistService.purgeExpired();

        assertFalse(blacklistService.isBlacklisted(expired.getJti()));
    }

    @Test
    void expiredRefreshTokens_arePurged() {
        RefreshToken expired = new RefreshToken();
        expired.setJti("test-rt-" + System.nanoTime());
        expired.setUsername(USERNAME);
        expired.setExpiresAt(LocalDateTime.now().minusMinutes(1));
        expired.setStatus(RefreshToken.STATUS_ACTIVE);
        refreshTokenMapper.insert(expired);

        refreshTokenService.purgeExpired();

        Long count = refreshTokenMapper.selectCount(new com.baomidou.mybatisplus.core.conditions.query
                .LambdaQueryWrapper<RefreshToken>().eq(RefreshToken::getJti, expired.getJti()));
        assertEquals(0L, count);
    }

    private JsonNode login() throws Exception {
        String body = "{\"username\":\"" + USERNAME + "\",\"password\":\"" + PASSWORD + "\"}";
        String resp = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data");
    }

    private JsonNode rotate(String refreshToken) throws Exception {
        String resp = mockMvc.perform(post("/api/auth/refresh")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"refreshToken\":\"" + refreshToken + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(resp).path("data");
    }

    private String jwtJti(String token) throws Exception {
        // 不验签解出 payload：取 jti（测试用）
        String payload = token.split("\\.")[1];
        return objectMapper.readTree(java.util.Base64.getUrlDecoder().decode(payload)).path("jti").asText();
    }
}
