package com.evops.auth.shiro;

import com.evops.auth.jwt.JwtPayload;
import org.apache.shiro.authc.AuthenticationToken;

/** 交给 Shiro Realm 的 JWT 凭证：已由过滤链完成签名/黑名单校验，载荷随令牌一并传递。 */
public class JwtAuthToken implements AuthenticationToken {

    private final String rawToken;
    private final JwtPayload payload;

    public JwtAuthToken(String rawToken, JwtPayload payload) {
        this.rawToken = rawToken;
        this.payload = payload;
    }

    public JwtPayload getPayload() {
        return payload;
    }

    @Override
    public Object getPrincipal() {
        return payload.getUsername();
    }

    @Override
    public Object getCredentials() {
        return rawToken;
    }
}
