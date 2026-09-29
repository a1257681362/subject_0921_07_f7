package com.evops.auth.jwt;

/** 令牌用途：ACCESS 访问业务接口；REFRESH 仅用于换新对，不能访问业务接口。 */
public enum TokenType {
    ACCESS,
    REFRESH
}
