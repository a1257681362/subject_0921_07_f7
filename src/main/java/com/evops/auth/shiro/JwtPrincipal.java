package com.evops.auth.shiro;

import java.util.Collections;
import java.util.List;

/**
 * Shiro 登录主体：用户名 + 令牌中的角色。
 * 仅含身份标识，不携带密码等敏感明文。
 */
public class JwtPrincipal {

    private final String username;
    private final List<String> roles;

    public JwtPrincipal(String username, List<String> roles) {
        this.username = username;
        this.roles = roles == null ? Collections.emptyList() : roles;
    }

    public String getUsername() { return username; }
    public List<String> getRoles() { return roles; }

    /** 日志/字符串展示只用用户名。 */
    @Override
    public String toString() {
        return username;
    }
}
