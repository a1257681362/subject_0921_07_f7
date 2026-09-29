package com.evops.auth.jwt;

import java.time.LocalDateTime;

/** 一次签发的结果：令牌原文 + 其 jti 与到期时刻（供刷新令牌台账登记，不必再反解）。 */
public class IssuedToken {

    private final String token;
    private final String jti;
    private final TokenType type;
    private final LocalDateTime expiresAt;

    public IssuedToken(String token, String jti, TokenType type, LocalDateTime expiresAt) {
        this.token = token;
        this.jti = jti;
        this.type = type;
        this.expiresAt = expiresAt;
    }

    public String getToken() { return token; }
    public String getJti() { return jti; }
    public TokenType getType() { return type; }
    public LocalDateTime getExpiresAt() { return expiresAt; }
}
