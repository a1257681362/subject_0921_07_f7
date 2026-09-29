package com.evops.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 刷新令牌台账。无状态会话中服务端只保留这一份"换新凭证"状态：
 * ACTIVE 有效，可换新一次；USED 已完成轮换（一次性）；REVOKED 登出或重放防护作废。
 * jti 与 JWT 中的 jti 一一对应；令牌原文不落库。
 */
@Data
@TableName("t_refresh_token")
public class RefreshToken {

    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_USED = "USED";
    public static final String STATUS_REVOKED = "REVOKED";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String jti;
    private String username;
    private LocalDateTime expiresAt;
    private String status;
    /** 轮换后指向新刷新令牌 jti，便于审计换新链路。 */
    private String replacedByJti;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
    private LocalDateTime usedTime;
    private LocalDateTime revokeTime;
}
