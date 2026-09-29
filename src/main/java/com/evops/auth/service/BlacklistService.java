package com.evops.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.evops.auth.entity.TokenBlacklistEntry;
import com.evops.auth.jwt.JwtPayload;
import com.evops.auth.mapper.TokenBlacklistMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.ZoneId;

/**
 * 访问令牌黑名单：登出的访问令牌按 jti 登记到其自然过期为止。
 * 命中黑名单的令牌即使签名有效也一律拒绝；过期登记由定时任务自动清理——
 * 过期令牌在校验环节本身就会被拒，登记无需长期保留。
 * 刷新令牌不进此表：它的有效/作废由 t_refresh_token 状态负责。
 */
@Service
public class BlacklistService {

    private static final Logger log = LoggerFactory.getLogger(BlacklistService.class);

    private final TokenBlacklistMapper blacklistMapper;

    public BlacklistService(TokenBlacklistMapper blacklistMapper) {
        this.blacklistMapper = blacklistMapper;
    }

    public boolean isBlacklisted(String jti) {
        if (jti == null) {
            return false;
        }
        Long count = blacklistMapper.selectCount(new LambdaQueryWrapper<TokenBlacklistEntry>()
                .eq(TokenBlacklistEntry::getJti, jti));
        return count != null && count > 0;
    }

    /** 登记黑名单；jti 唯一约束兜底重复登出/并发登记，重复登记按幂等处理。 */
    public void add(JwtPayload payload, String reason) {
        TokenBlacklistEntry entry = new TokenBlacklistEntry();
        entry.setJti(payload.getJti());
        entry.setUsername(payload.getUsername());
        entry.setTokenType(payload.getType().name());
        entry.setExpiresAt(LocalDateTime.ofInstant(payload.getExpiration().toInstant(),
                ZoneId.systemDefault()));
        entry.setReason(reason);
        try {
            blacklistMapper.insert(entry);
        } catch (DuplicateKeyException ignored) {
            // 已登记过（如同一会话连续登出），幂等成功。
        }
    }

    /**
     * 定时清理过期登记。cron 由 auth.jwt.blacklist-cleanup-cron 配置（默认每 10 分钟）。
     * 异常只记录不外抛，避免调度线程中断后续执行。
     */
    @Scheduled(cron = "${auth.jwt.blacklist-cleanup-cron:0 */10 * * * ?}")
    public void purgeExpired() {
        try {
            int deleted = blacklistMapper.delete(new LambdaQueryWrapper<TokenBlacklistEntry>()
                    .lt(TokenBlacklistEntry::getExpiresAt, LocalDateTime.now()));
            if (deleted > 0) {
                log.info("黑名单过期登记自动清理：{} 条", deleted);
            }
        } catch (Exception e) {
            log.error("黑名单清理失败：{}", e.getMessage(), e);
        }
    }
}
