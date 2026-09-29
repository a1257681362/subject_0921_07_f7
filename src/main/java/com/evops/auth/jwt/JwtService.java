package com.evops.auth.jwt;

import com.evops.config.AuthProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.security.Key;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * JWT 签发与校验（HS256）。令牌 claims：
 * sub 用户名、jti 唯一标识、typ ACCESS/REFRESH、roles 角色、iat/exp 时间。
 * 不放密码等敏感明文；无状态，服务端不保存会话。
 */
@Component
public class JwtService {

    /** claims 中的令牌类型键。 */
    public static final String CLAIM_TYPE = "typ";
    public static final String CLAIM_ROLES = "roles";

    private final AuthProperties properties;
    private Key signingKey;
    private long accessTtlMillis;
    private long refreshTtlMillis;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        AuthProperties.Jwt jwt = properties.getJwt();
        byte[] keyBytes = Base64.getDecoder().decode(jwt.getSecret());
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.accessTtlMillis = TimeUnit.MINUTES.toMillis(jwt.getAccessTtlMinutes());
        this.refreshTtlMillis = TimeUnit.HOURS.toMillis(jwt.getRefreshTtlHours());
    }

    /** 签发访问令牌。 */
    public IssuedToken issueAccessToken(String username, List<String> roles) {
        return issueAccessToken(username, roles, UUID.randomUUID().toString());
    }

    /** 签发访问令牌（指定 jti，刷新轮换链路使用）。 */
    public IssuedToken issueAccessToken(String username, List<String> roles, String jti) {
        return build(username, roles, TokenType.ACCESS, accessTtlMillis, jti);
    }

    /** 签发刷新令牌：只承担换新，typ=REFRESH。 */
    public IssuedToken issueRefreshToken(String username, List<String> roles) {
        return issueRefreshToken(username, roles, UUID.randomUUID().toString());
    }

    /** 签发刷新令牌（指定 jti）。 */
    public IssuedToken issueRefreshToken(String username, List<String> roles, String jti) {
        return build(username, roles, TokenType.REFRESH, refreshTtlMillis, jti);
    }

    private IssuedToken build(String username, List<String> roles, TokenType type,
                              long ttlMillis, String jti) {
        Date now = new Date();
        Date expires = new Date(now.getTime() + ttlMillis);
        String token = Jwts.builder()
                .setSubject(username)
                .setId(jti)
                .claim(CLAIM_TYPE, type.name())
                .claim(CLAIM_ROLES, roles == null ? Collections.emptyList() : roles)
                .setIssuedAt(now)
                .setExpiration(expires)
                .signWith(signingKey)
                .compact();
        LocalDateTime expiresAt = LocalDateTime.ofInstant(Instant.ofEpochMilli(expires.getTime()),
                ZoneId.systemDefault());
        return new IssuedToken(token, jti, type, expiresAt);
    }

    /**
     * 校验签名与有效期并解析载荷。过期与非法（篡改/格式错误）分开抛，
     * 便于过滤链给出明确的 401 原因。
     */
    public JwtPayload parse(String token) throws ExpiredJwtException, JwtException {
        Claims claims = Jwts.parserBuilder()
                .setSigningKey(signingKey)
                .build()
                .parseClaimsJws(token)
                .getBody();
        String typeRaw = claims.get(CLAIM_TYPE, String.class);
        TokenType type;
        try {
            type = TokenType.valueOf(typeRaw);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new JwtException("令牌类型缺失或非法");
        }
        Object rawRoles = claims.get(CLAIM_ROLES);
        List<String> roles = rawRoles instanceof List ? (List<String>) rawRoles : Collections.emptyList();
        return new JwtPayload(claims.getSubject(), claims.getId(), type, roles, claims.getExpiration());
    }
}
