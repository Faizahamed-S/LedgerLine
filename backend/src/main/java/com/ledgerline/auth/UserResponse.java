package com.ledgerline.auth;

import com.ledgerline.domain.Role;
import com.ledgerline.domain.User;

public record UserResponse(Long id, String email, String fullName, Role role) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getFullName(), user.getRole());
    }
}
