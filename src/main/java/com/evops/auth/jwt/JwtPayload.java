package com.evops.auth.jwt;

import java.util.Collections;
import java.util.Date;
import java.util.List;

/**
 * 校验通过后的令牌载荷。只保留登录标识与角色，
 * 令牌中绝不放密码、姓名、手机号等任何敏感明文。
 */
public class JwtPayload {

    private final String username;
    private final String jti;
    private final TokenType type;
    private final List<String> roles;
    private final Date expiration;

    public JwtPayload(String username, String jti, TokenType type,
                      List<String> roles, Date expiration) {
        this.username = username;
        this.jti = jti;
        this.type = type;
        this.roles = roles == null ? Collections.emptyList() : roles;
        this.expiration = expiration;
    }

    public String getUsername() { return username; }
    public String getJti() { return jti; }
    public TokenType getType() { return type; }
    public List<String> getRoles() { return roles; }
    public Date getExpiration() { return expiration; }

    /** 访问令牌的 Shiro principal：只暴露用户名，敏感信息不进会话。 */
    public String principal() {
        return username;
    }
}
