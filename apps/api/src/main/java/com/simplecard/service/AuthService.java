package com.simplecard.service;

import com.simplecard.model.request.LoginRequest;
import com.simplecard.model.request.RegisterRequest;
import com.simplecard.model.response.AuthResponse;
import com.simplecard.model.response.CaptchaResponse;

public interface AuthService {

    CaptchaResponse generateCaptcha();

    AuthResponse register(RegisterRequest request);

    AuthResponse login(LoginRequest request, String sessionToken);

    void logout();
}
