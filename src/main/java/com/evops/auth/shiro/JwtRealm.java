package com.evops.auth.shiro;

import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationInfo;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.authc.SimpleAuthenticationInfo;
import org.apache.shiro.realm.AuthenticatingRealm;
import org.springframework.stereotype.Component;

/**
 * JWT 专用 Realm：只认 JwtAuthToken，只承担认证（不实现 Authorizer，
 * 避免被 Shiro 自动配置识别为 Authorizer 类型 Bean 而挤掉其默认装配）。
 * 签名、有效期、黑名单、令牌类型已在过滤链完成校验，
 * Realm 只负责把载荷落成 Shiro 登录主体；角色取自令牌 claims 随主体保留，
 * 不查任何敏感信息。后续需要 @RequiresRoles 等授权时再单独提供 Authorizer 装配。
 */
@Component
public class JwtRealm extends AuthenticatingRealm {

    @Override
    public boolean supports(AuthenticationToken token) {
        return token instanceof JwtAuthToken;
    }

    @Override
    protected AuthenticationInfo doGetAuthenticationInfo(AuthenticationToken token) throws AuthenticationException {
        JwtAuthToken jwtToken = (JwtAuthToken) token;
        JwtPrincipal principal = new JwtPrincipal(
                jwtToken.getPayload().getUsername(), jwtToken.getPayload().getRoles());
        return new SimpleAuthenticationInfo(principal, jwtToken.getCredentials(), getName());
    }
}
