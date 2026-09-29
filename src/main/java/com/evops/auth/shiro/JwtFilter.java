package com.evops.auth.shiro;

import com.evops.auth.jwt.JwtPayload;
import com.evops.auth.jwt.JwtService;
import com.evops.auth.jwt.TokenType;
import com.evops.auth.service.BlacklistService;
import com.evops.common.ApiResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.AuthenticationToken;
import org.apache.shiro.web.filter.authc.AuthenticatingFilter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;

/**
 * Shiro 过滤链中的 JWT 校验过滤器（过滤器名 jwt）。
 * 职责：取 Bearer 令牌 → 验签/验有效期 → 拒用刷新令牌 → 查黑名单 → 交 JwtRealm 建立登录态。
 * 任何一关不过都直接回 HTTP 401 + 统一 JSON 结构，不进入控制器；全程不创建服务端会话。
 */
public class JwtFilter extends AuthenticatingFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);

    public static final String AUTHORIZATION_HEADER = "Authorization";
    public static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final BlacklistService blacklistService;
    private final ObjectMapper objectMapper;

    public JwtFilter(JwtService jwtService, BlacklistService blacklistService, ObjectMapper objectMapper) {
        this.jwtService = jwtService;
        this.blacklistService = blacklistService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected AuthenticationToken createToken(ServletRequest request, ServletResponse response) {
        // 本过滤器在 onAccessDenied 中直接构造 JwtAuthToken 登录，不走父类的模板方法。
        throw new UnsupportedOperationException("JwtFilter 直接登录，不使用 createToken");
    }

    @Override
    protected boolean isAccessAllowed(ServletRequest request, ServletResponse response, Object mappedValue) {
        // 无状态令牌：每次请求都在 onAccessDenied 里现验，不依赖任何既有登录态。
        return false;
    }

    @Override
    protected boolean onAccessDenied(ServletRequest servletRequest, ServletResponse servletResponse) throws Exception {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        String header = request.getHeader(AUTHORIZATION_HEADER);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            writeUnauthorized(response, "未携带登录令牌，请先登录");
            return false;
        }
        String rawToken = header.substring(BEARER_PREFIX.length()).trim();
        if (rawToken.isEmpty()) {
            writeUnauthorized(response, "登录令牌为空，请重新登录");
            return false;
        }

        JwtPayload payload;
        try {
            payload = jwtService.parse(rawToken);
        } catch (ExpiredJwtException e) {
            writeUnauthorized(response, "访问令牌已过期，请使用刷新令牌换新或重新登录");
            return false;
        } catch (JwtException | IllegalArgumentException e) {
            log.warn("非法 JWT 被拒绝：{}", e.getMessage());
            writeUnauthorized(response, "登录令牌无效，请重新登录");
            return false;
        }

        if (payload.getType() != TokenType.ACCESS) {
            writeUnauthorized(response, "令牌类型错误，访问业务接口须使用访问令牌");
            return false;
        }
        if (blacklistService.isBlacklisted(payload.getJti())) {
            writeUnauthorized(response, "登录状态已登出，请重新登录");
            return false;
        }

        try {
            getSubject(request, response).login(new JwtAuthToken(rawToken, payload));
            return true;
        } catch (AuthenticationException e) {
            writeUnauthorized(response, "登录令牌校验失败，请重新登录");
            return false;
        }
    }

    private void writeUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(ApiResponse.fail(message)));
    }
}
