package com.evops.auth.controller;

import com.evops.auth.dto.LoginRequest;
import com.evops.auth.dto.LogoutRequest;
import com.evops.auth.dto.RefreshRequest;
import com.evops.auth.service.AuthService;
import com.evops.auth.shiro.JwtFilter;
import com.evops.auth.vo.TokenPair;
import com.evops.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.Valid;

/**
 * 认证入口：登录领双令牌、刷新换新、登出进黑名单。
 * 登录/刷新在 Shiro 过滤链中放行（anon）；登出需携带有效访问令牌（jwt）。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/login")
    public ApiResponse<TokenPair> login(@Valid @RequestBody LoginRequest request) {
        return ApiResponse.ok(authService.login(request));
    }

    @PostMapping("/refresh")
    public ApiResponse<TokenPair> refresh(@Valid @RequestBody RefreshRequest request) {
        return ApiResponse.ok(authService.refresh(request.getRefreshToken()));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout(
            @RequestHeader(JwtFilter.AUTHORIZATION_HEADER) String authorization,
            @RequestBody(required = false) LogoutRequest request) {
        String accessToken = authorization.startsWith(JwtFilter.BEARER_PREFIX)
                ? authorization.substring(JwtFilter.BEARER_PREFIX.length()).trim()
                : authorization.trim();
        String refreshToken = request == null ? null : request.getRefreshToken();
        authService.logout(accessToken, refreshToken);
        return ApiResponse.ok(null);
    }
}
