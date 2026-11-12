package com.simplecard.controller;

import com.simplecard.common.ApiResponse;
import com.simplecard.common.PageResult;
import com.simplecard.context.RequestContext;
import com.simplecard.model.request.ChangePasswordRequest;
import com.simplecard.model.response.UserProfileResponse;
import com.simplecard.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/user")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/profile")
    public ApiResponse<UserProfileResponse> getProfile() {
        return ApiResponse.success(userService.getProfile(RequestContext.getUserId()));
    }

    @PutMapping("/password")
    public ApiResponse<Void> changePassword(@Valid @RequestBody ChangePasswordRequest request) {
        userService.changePassword(RequestContext.getUserId(), request);
        return ApiResponse.success();
    }

    @GetMapping("/orders")
    public ApiResponse<PageResult<?>> getOrders(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize,
            @RequestParam(required = false) String status) {
        return ApiResponse.success(userService.getOrders(RequestContext.getUserId(), status, page, pageSize));
    }

    @GetMapping("/points")
    public ApiResponse<Map<String, Object>> getPoints(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(value = "page_size", defaultValue = "20") int pageSize) {
        return ApiResponse.success(userService.getPoints(RequestContext.getUserId(), page, pageSize));
    }
}
