package com.evops.auth.vo;

import java.time.LocalDateTime;

/**
 * 双令牌返回体。只给令牌本身与到期时刻，
 * 不回显密码、姓名、手机号等敏感明文。
 */
public class TokenPair {

    private String accessToken;
    private String refreshToken;
    private String tokenType = "Bearer";
    private LocalDateTime accessExpiresAt;
    private LocalDateTime refreshExpiresAt;

    public TokenPair(String accessToken, String refreshToken,
                     LocalDateTime accessExpiresAt, LocalDateTime refreshExpiresAt) {
        this.accessToken = accessToken;
        this.refreshToken = refreshToken;
        this.accessExpiresAt = accessExpiresAt;
        this.refreshExpiresAt = refreshExpiresAt;
    }

    public String getAccessToken() { return accessToken; }
    public String getRefreshToken() { return refreshToken; }
    public String getTokenType() { return tokenType; }
    public LocalDateTime getAccessExpiresAt() { return accessExpiresAt; }
    public LocalDateTime getRefreshExpiresAt() { return refreshExpiresAt; }
}
