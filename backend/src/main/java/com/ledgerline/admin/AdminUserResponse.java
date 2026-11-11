package com.ledgerline.admin;

import com.ledgerline.domain.Role;
import com.ledgerline.domain.User;
import com.ledgerline.domain.UserStatus;

import java.time.Instant;

public record AdminUserResponse(Long id, String email, String fullName, Role role, UserStatus status,
                                Instant createdAt) {

    public static AdminUserResponse from(User user) {
        return new AdminUserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole(),
                user.getStatus(), user.getCreatedAt());
    }
}
