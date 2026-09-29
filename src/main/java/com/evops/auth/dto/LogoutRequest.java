package com.evops.auth.dto;

/** 登出请求：访问令牌从 Authorization 头识别；刷新令牌随体上送以一并吊销（可选）。 */
public class LogoutRequest {

    private String refreshToken;

    public String getRefreshToken() { return refreshToken; }
    public void setRefreshToken(String refreshToken) { this.refreshToken = refreshToken; }
}
