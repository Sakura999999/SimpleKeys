package com.simplecard.service;

import com.simplecard.common.PageResult;

import java.util.UUID;

public interface AdminUserService {

    PageResult<?> listUsers(String keyword, int page, int pageSize);

    void toggleUser(UUID id, int isDeleted);
}
