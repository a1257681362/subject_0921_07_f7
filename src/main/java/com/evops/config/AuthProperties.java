package com.evops.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;

/**
 * 无状态会话 JWT 配置（application.yml 中 auth.jwt 前缀）。
 * 密钥为 Base64 编码的 HMAC 密钥，解码后至少 32 字节（HS256 下限）。
 */
@Component
@ConfigurationProperties(prefix = "auth")
public class AuthProperties {

    /** 鉴权总开关：测试环境可关闭以放行既有业务用例，生产必须为 true（默认）。 */
    private boolean enabled = true;

    private Jwt jwt = new Jwt();

    /** 登录账号（起步阶段的内置账号，接入用户表后可移除）。 */
    private List<Account> accounts = new ArrayList<>();

    @PostConstruct
    public void validate() {
        jwt.validate();
        if (enabled && accounts.isEmpty()) {
            throw new IllegalStateException("auth.enabled=true 时至少配置一个登录账号 auth.accounts");
        }
    }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }
    public Jwt getJwt() { return jwt; }
    public void setJwt(Jwt jwt) { this.jwt = jwt; }
    public List<Account> getAccounts() { return accounts; }
    public void setAccounts(List<Account> accounts) { this.accounts = accounts; }

    public static class Jwt {
        /** Base64 编码的签名密钥，生产用环境变量 AUTH_JWT_SECRET 覆盖。 */
        private String secret;
        /** 访问令牌有效期（分钟）。 */
        private long accessTtlMinutes = 120;
        /** 刷新令牌有效期（小时）。 */
        private long refreshTtlHours = 168;
        /** 黑名单过期登记清理 cron。 */
        private String blacklistCleanupCron = "0 */10 * * * ?";

        public void validate() {
            if (secret == null || secret.trim().isEmpty()) {
                throw new IllegalStateException("auth.jwt.secret 未配置，请设置 AUTH_JWT_SECRET（Base64，解码后不少于32字节）");
            }
            byte[] keyBytes;
            try {
                keyBytes = Base64.getDecoder().decode(secret);
            } catch (IllegalArgumentException e) {
                throw new IllegalStateException("auth.jwt.secret 不是合法 Base64");
            }
            if (keyBytes.length < 32) {
                throw new IllegalStateException("auth.jwt.secret 解码后长度不足 32 字节，不满足 HS256 安全下限");
            }
            if (accessTtlMinutes <= 0 || refreshTtlHours <= 0) {
                throw new IllegalStateException("JWT 令牌有效期必须为正数");
            }
        }

        public String getSecret() { return secret; }
        public void setSecret(String secret) { this.secret = secret; }
        public long getAccessTtlMinutes() { return accessTtlMinutes; }
        public void setAccessTtlMinutes(long accessTtlMinutes) { this.accessTtlMinutes = accessTtlMinutes; }
        public long getRefreshTtlHours() { return refreshTtlHours; }
        public void setRefreshTtlHours(long refreshTtlHours) { this.refreshTtlHours = refreshTtlHours; }
        public String getBlacklistCleanupCron() { return blacklistCleanupCron; }
        public void setBlacklistCleanupCron(String blacklistCleanupCron) { this.blacklistCleanupCron = blacklistCleanupCron; }
    }

    /** 内置账号：username/password 登录，roles 写入令牌（不写敏感明文）。 */
    public static class Account {
        private String username;
        private String password;
        private List<String> roles = new ArrayList<>(Arrays.asList("system"));

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }
        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }
        public List<String> getRoles() { return roles; }
        public void setRoles(List<String> roles) { this.roles = roles; }
    }
}
