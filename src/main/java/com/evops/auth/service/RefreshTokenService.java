package com.evops.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.evops.auth.entity.RefreshToken;
import com.evops.auth.mapper.RefreshTokenMapper;
import com.evops.common.BusinessException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;

/**
 * 刷新令牌台账服务。刷新令牌一次性使用：
 * 每次换新必须把旧令牌 ACTIVE→USED 原子翻转（带状态条件的 UPDATE 兜底并发与重放），
 * 翻转成功才签发新令牌对；任何非 ACTIVE（已轮换/已登出/不存在）或已过期的刷新令牌一律拒绝。
 */
@Service
public class RefreshTokenService {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenService.class);

    private final RefreshTokenMapper refreshTokenMapper;
    /** 检测到重放时的令牌族吊销要独立提交（本事务随后抛异常回滚），用 REQUIRES_NEW。 */
    private final TransactionTemplate requiresNew;

    public RefreshTokenService(RefreshTokenMapper refreshTokenMapper,
                               org.springframework.transaction.PlatformTransactionManager transactionManager) {
        this.refreshTokenMapper = refreshTokenMapper;
        this.requiresNew = new TransactionTemplate(transactionManager);
        this.requiresNew.setPropagationBehavior(
                org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** 登录成功后登记一枚 ACTIVE 刷新令牌。 */
    public void register(String jti, String username, LocalDateTime expiresAt) {
        RefreshToken record = new RefreshToken();
        record.setJti(jti);
        record.setUsername(username);
        record.setExpiresAt(expiresAt);
        record.setStatus(RefreshToken.STATUS_ACTIVE);
        refreshTokenMapper.insert(record);
    }

    /**
     * 消费刷新令牌并指向新令牌。条件更新保证：
     * 正常首次换新 1 行生效；同一令牌二次提交/并发重放 0 行生效，直接拒绝。
     */
    @Transactional
    public void consume(String jti, String replacedByJti) {
        RefreshToken record = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                .eq(RefreshToken::getJti, jti));
        if (record == null) {
            throw new BusinessException("刷新令牌无效，请重新登录");
        }
        if (LocalDateTime.now().isAfter(record.getExpiresAt())) {
            throw new BusinessException("刷新令牌已过期，请重新登录");
        }
        if (!RefreshToken.STATUS_ACTIVE.equals(record.getStatus())) {
            if (RefreshToken.STATUS_REVOKED.equals(record.getStatus())) {
                throw new BusinessException("刷新令牌已登出，请重新登录");
            }
            // USED 令牌被二次使用：一次性令牌重放，强烈暗示已泄露。
            // 沿换新链路吊销整个令牌族当前仍有效的最新一枚，让窃取者拿到的新令牌同时失效。
            // 独立事务提交：本方法随后抛业务异常，外层事务会回滚，吊销不能跟着回滚。
            log.warn("刷新令牌重复使用（疑似泄露），吊销令牌族：jti={}", jti);
            String chainHead = record.getReplacedByJti();
            requiresNew.executeWithoutResult(status -> doRevokeChain(chainHead));
            throw new BusinessException("刷新令牌已使用，请重新登录");
        }
        int updated = refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getId, record.getId())
                .eq(RefreshToken::getStatus, RefreshToken.STATUS_ACTIVE)
                .set(RefreshToken::getStatus, RefreshToken.STATUS_USED)
                .set(RefreshToken::getReplacedByJti, replacedByJti)
                .set(RefreshToken::getUsedTime, LocalDateTime.now()));
        if (updated == 0) {
            // 并发换新抢先一步，本笔失败。
            throw new BusinessException("刷新令牌已使用，请重新登录");
        }
    }

    /** 登出：ACTIVE 刷新令牌置 REVOKED；其他状态幂等不动。 */
    @Transactional
    public void revoke(String jti) {
        refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                .eq(RefreshToken::getJti, jti)
                .eq(RefreshToken::getStatus, RefreshToken.STATUS_ACTIVE)
                .set(RefreshToken::getStatus, RefreshToken.STATUS_REVOKED)
                .set(RefreshToken::getRevokeTime, LocalDateTime.now()));
    }

    /**
     * 令牌族吊销：沿 replaced_by_jti 链找到最新一枚仍 ACTIVE 的刷新令牌并吊销。
     * 用于检测到旧令牌被重放（疑似泄露）时，连同已轮换出的新令牌一起失效。
     * 链条上的历史令牌本就是 USED，无需再动；链断（已登出/已清理）即结束。
     */
    private void doRevokeChain(String nextJti) {
        String jti = nextJti;
        int guard = 0;
        while (jti != null && guard++ < 1000) {
            RefreshToken current = refreshTokenMapper.selectOne(new LambdaQueryWrapper<RefreshToken>()
                    .eq(RefreshToken::getJti, jti));
            if (current == null) {
                return;
            }
            if (RefreshToken.STATUS_ACTIVE.equals(current.getStatus())) {
                // 最新有效一枚：吊销。它可能已再次换新成 USED，那时继续沿链向前。
                int updated = refreshTokenMapper.update(null, new LambdaUpdateWrapper<RefreshToken>()
                        .eq(RefreshToken::getId, current.getId())
                        .eq(RefreshToken::getStatus, RefreshToken.STATUS_ACTIVE)
                        .set(RefreshToken::getStatus, RefreshToken.STATUS_REVOKED)
                        .set(RefreshToken::getRevokeTime, LocalDateTime.now()));
                if (updated > 0) {
                    return;
                }
            }
            if (RefreshToken.STATUS_USED.equals(current.getStatus())) {
                jti = current.getReplacedByJti();
                continue;
            }
            return;
        }
    }

    /**
     * 定时清理过期令牌登记（含未使用但已过期的）。
     * 过期 JWT 在解析环节就会被拒，台账登记无需保留；异常不外抛。
     */
    @Scheduled(cron = "${auth.jwt.blacklist-cleanup-cron:0 */10 * * * ?}")
    public void purgeExpired() {
        try {
            int deleted = refreshTokenMapper.delete(new LambdaQueryWrapper<RefreshToken>()
                    .lt(RefreshToken::getExpiresAt, LocalDateTime.now()));
            if (deleted > 0) {
                log.info("过期刷新令牌登记自动清理：{} 条", deleted);
            }
        } catch (Exception e) {
            log.error("刷新令牌清理失败：{}", e.getMessage(), e);
        }
    }
}
