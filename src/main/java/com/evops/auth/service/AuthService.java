package com.evops.auth.service;

import com.evops.auth.dto.LoginRequest;
import com.evops.auth.jwt.IssuedToken;
import com.evops.auth.jwt.JwtPayload;
import com.evops.auth.jwt.JwtService;
import com.evops.auth.jwt.TokenType;
import com.evops.auth.entity.TokenBlacklistEntry;
import com.evops.auth.vo.TokenPair;
import com.evops.common.BusinessException;
import com.evops.config.AuthProperties;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import org.apache.shiro.SecurityUtils;
import org.apache.shiro.authc.AuthenticationException;
import org.apache.shiro.authc.UsernamePasswordToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * 认证编排：登录发双令牌、刷新一次性轮换、登出黑名单。
 * 会话状态全部体现在令牌自身（无状态）+ 两张轻量表（刷新台账、黑名单）。
 */
@Service
public class AuthService {

    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final BlacklistService blacklistService;
    private final AuthProperties properties;

    public AuthService(JwtService jwtService,
                       RefreshTokenService refreshTokenService,
                       BlacklistService blacklistService,
                       AuthProperties properties) {
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.blacklistService = blacklistService;
        this.properties = properties;
    }

    /**
     * 登录：Shiro 账号密码校验（localRealm）通过后签发双令牌，刷新令牌登记 ACTIVE。
     * 账号密码不落 JWT，不回显敏感明文。
     */
    @Transactional
    public TokenPair login(LoginRequest request) {
        UsernamePasswordToken shiroToken = new UsernamePasswordToken(request.getUsername(), request.getPassword());
        try {
            SecurityUtils.getSubject().login(shiroToken);
        } catch (AuthenticationException e) {
            throw new BusinessException("用户名或密码不正确");
        }
        List<String> roles = rolesOf(request.getUsername());
        IssuedToken access = jwtService.issueAccessToken(request.getUsername(), roles);
        IssuedToken refresh = jwtService.issueRefreshToken(request.getUsername(), roles);
        refreshTokenService.register(refresh.getJti(), request.getUsername(), refresh.getExpiresAt());
        return new TokenPair(access.getToken(), refresh.getToken(),
                access.getExpiresAt(), refresh.getExpiresAt());
    }

    /**
     * 刷新：旧刷新令牌必须 ACTIVE，先原子翻转 USED（一次性，重放/并发第二笔必失败），
     * 再签发并登记全新令牌对——刷新即轮换，access 与 refresh 都换新。
     */
    @Transactional
    public TokenPair refresh(String rawRefreshToken) {
        JwtPayload payload;
        try {
            payload = jwtService.parse(rawRefreshToken);
        } catch (ExpiredJwtException e) {
            throw new BusinessException("刷新令牌已过期，请重新登录");
        } catch (JwtException | IllegalArgumentException e) {
            throw new BusinessException("刷新令牌无效，请重新登录");
        }
        if (payload.getType() != TokenType.REFRESH) {
            throw new BusinessException("令牌类型错误，请使用刷新令牌换新");
        }

        String newRefreshJti = UUID.randomUUID().toString();
        // 先占用旧令牌：条件更新成功才继续，杜绝一次性令牌被二次使用。
        refreshTokenService.consume(payload.getJti(), newRefreshJti);

        List<String> roles = payload.getRoles();
        IssuedToken access = jwtService.issueAccessToken(payload.getUsername(), roles);
        IssuedToken refresh = jwtService.issueRefreshToken(payload.getUsername(), roles, newRefreshJti);
        refreshTokenService.register(newRefreshJti, payload.getUsername(), refresh.getExpiresAt());
        return new TokenPair(access.getToken(), refresh.getToken(),
                access.getExpiresAt(), refresh.getExpiresAt());
    }

    /**
     * 登出：当前访问令牌进黑名单（到自然过期为止，定时清理）；
     * 随体上送的刷新令牌一并吊销。刷新令牌缺失或已过期按尽力处理，不影响登出成功。
     */
    @Transactional
    public void logout(String rawAccessToken, String rawRefreshToken) {
        JwtPayload accessPayload;
        try {
            accessPayload = jwtService.parse(rawAccessToken);
        } catch (ExpiredJwtException e) {
            // 能进到登出接口说明过滤链已验过；过期令牌无法登记黑名单，但它本身也已不可用。
            throw new BusinessException("访问令牌已过期，无需登出");
        } catch (JwtException | IllegalArgumentException e) {
            // 能进到登出接口说明过滤链已验过；解析异常时无法登记，令牌本身也不可用了。
            throw new BusinessException("访问令牌无效，无需登出");
        }
        if (accessPayload.getType() == TokenType.ACCESS) {
            blacklistService.add(accessPayload, TokenBlacklistEntry.REASON_LOGOUT);
        }
        if (rawRefreshToken != null && !rawRefreshToken.trim().isEmpty()) {
            String token = rawRefreshToken.trim();
            try {
                JwtPayload refreshPayload = jwtService.parse(token);
                if (refreshPayload.getType() == TokenType.REFRESH) {
                    refreshTokenService.revoke(refreshPayload.getJti());
                }
            } catch (ExpiredJwtException e) {
                // 令牌虽过期，jti 仍可从 claims 取出：把仍 ACTIVE 的台账登记吊销，保持登出语义完整。
                String jti = e.getClaims().getId();
                if (jti != null) {
                    refreshTokenService.revoke(jti);
                }
            } catch (JwtException | IllegalArgumentException ignored) {
                // 非法刷新令牌与登出无关，忽略，不影响访问令牌已入黑名单。
            }
        }
    }

    private List<String> rolesOf(String username) {
        return properties.getAccounts().stream()
                .filter(a -> username.equals(a.getUsername()))
                .map(AuthProperties.Account::getRoles)
                .findFirst()
                .orElse(Collections.emptyList());
    }
}
