package com.ledgerline.security;

import com.ledgerline.domain.Role;
import org.springframework.security.core.AuthenticatedPrincipal;

public record CurrentUser(Long id, String email, Role role) implements AuthenticatedPrincipal {

    @Override
    public String getName() {
        return String.valueOf(id);
    }
}
