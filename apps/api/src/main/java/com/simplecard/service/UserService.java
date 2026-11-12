package com.simplecard.service;

import com.simplecard.common.PageResult;
import com.simplecard.model.request.ChangePasswordRequest;
import com.simplecard.model.response.UserProfileResponse;

import java.util.Map;
import java.util.UUID;

public interface UserService {

    UserProfileResponse getProfile(UUID userId);

    void changePassword(UUID userId, ChangePasswordRequest request);

    PageResult<?> getOrders(UUID userId, String status, int page, int pageSize);

    Map<String, Object> getPoints(UUID userId, int page, int pageSize);
}
