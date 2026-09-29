package com.evops.auth.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 令牌黑名单：登出的访问令牌在此登记到其自然过期为止，过滤链命中即拒绝。
 * 只登记 jti 与过期时刻，不保存令牌原文；过期登记由定时任务自动清理。
 */
@Data
@TableName("t_token_blacklist")
public class TokenBlacklistEntry {

    public static final String REASON_LOGOUT = "LOGOUT";

    @TableId(type = IdType.AUTO)
    private Long id;
    private String jti;
    private String username;
    private String tokenType;
    private LocalDateTime expiresAt;
    private String reason;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createTime;
}
