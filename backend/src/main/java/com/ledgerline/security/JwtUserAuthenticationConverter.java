package com.ledgerline.security;

import com.ledgerline.domain.User;
import com.ledgerline.domain.UserStatus;
import com.ledgerline.repository.UserRepository;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.DisabledException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

/**
 * Resolves the JWT subject to a live user row on every request, so a role change or a disable takes effect
 * immediately rather than when the token expires. Not a Spring bean on purpose: Converter beans are
 * auto-registered into MVC's conversion service.
 */
public class JwtUserAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

    private final UserRepository userRepository;

    public JwtUserAuthenticationConverter(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public AbstractAuthenticationToken convert(Jwt jwt) {
        User user = userRepository.findById(parseUserId(jwt.getSubject()))
                .orElseThrow(() -> new BadCredentialsException("Unknown user"));
        if (user.getStatus() != UserStatus.ACTIVE) {
            throw new DisabledException("User is disabled");
        }
        CurrentUser principal = new CurrentUser(user.getId(), user.getEmail(), user.getRole());
        return UsernamePasswordAuthenticationToken.authenticated(
                principal, jwt, List.of(new SimpleGrantedAuthority("ROLE_" + user.getRole().name())));
    }

    private static Long parseUserId(String subject) {
        try {
            return Long.valueOf(subject);
        } catch (NumberFormatException e) {
            throw new BadCredentialsException("Invalid token subject");
        }
    }
}
