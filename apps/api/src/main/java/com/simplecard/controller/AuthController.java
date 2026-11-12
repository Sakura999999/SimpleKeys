package com.simplecard.controller;

import com.simplecard.common.ApiResponse;
import com.simplecard.model.request.LoginRequest;
import com.simplecard.model.request.RegisterRequest;
import com.simplecard.model.response.AuthResponse;
import com.simplecard.model.response.CaptchaResponse;
import com.simplecard.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @GetMapping("/captcha")
    public ApiResponse<CaptchaResponse> getCaptcha() {
        return ApiResponse.success(authService.generateCaptcha());
    }

    @PostMapping("/register")
    public ApiResponse<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ApiResponse.success(authService.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<AuthResponse> login(@Valid @RequestBody LoginRequest request,
                                           @RequestHeader(value = "X-Session-Token", required = false) String sessionToken) {
        return ApiResponse.success(authService.login(request, sessionToken));
    }

    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.success();
    }
}
