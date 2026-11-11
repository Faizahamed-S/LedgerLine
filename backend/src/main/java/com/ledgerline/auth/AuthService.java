package com.ledgerline.auth;

import com.ledgerline.audit.AuditAction;
import com.ledgerline.audit.AuditService;
import com.ledgerline.common.ConflictException;
import com.ledgerline.common.InvalidCredentialsException;
import com.ledgerline.common.NotFoundException;
import com.ledgerline.domain.Role;
import com.ledgerline.domain.User;
import com.ledgerline.domain.UserStatus;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.CurrentUser;
import com.ledgerline.security.IssuedToken;
import com.ledgerline.security.TokenService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;
import java.util.Optional;

@Service
public class AuthService {

    static final String INVALID_CREDENTIALS = "Invalid email or password";

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final TokenService tokenService;
    private final AuditService auditService;
    private final String dummyPasswordHash;

    public AuthService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       TokenService tokenService,
                       AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.dummyPasswordHash = passwordEncoder.encode("timing-equalisation-dummy-password");
    }

    @Transactional
    public UserResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email is already registered");
        }
        User user = userRepository.save(new User(
                request.email(), passwordEncoder.encode(request.password()), request.fullName(), Role.HOLDER));
        auditService.record(user.getId(), AuditAction.USER_REGISTERED,
                AuditService.TARGET_USER, user.getId(), Map.of("email", user.getEmail()));
        return UserResponse.from(user);
    }

    /**
     * Deliberately not @Transactional: the LOGIN_FAILED audit row must commit even though the method then throws.
     * Unknown email, wrong password and disabled account all get the same error, and the password is always
     * checked against some hash so response time does not reveal which emails exist.
     */
    public LoginResponse login(LoginRequest request) {
        Optional<User> found = userRepository.findByEmail(request.email());
        String hash = found.map(User::getPasswordHash).orElse(dummyPasswordHash);
        boolean passwordMatches = passwordEncoder.matches(request.password(), hash);

        if (found.isEmpty() || !passwordMatches || found.get().getStatus() != UserStatus.ACTIVE) {
            auditService.record(null, AuditAction.LOGIN_FAILED, null, null, Map.of("email", request.email()));
            throw new InvalidCredentialsException(INVALID_CREDENTIALS);
        }

        User user = found.get();
        auditService.record(user.getId(), AuditAction.LOGIN_SUCCESS,
                AuditService.TARGET_USER, user.getId(), Map.of("email", user.getEmail()));
        IssuedToken token = tokenService.issue(user);
        return new LoginResponse(token.token(), token.expiresAt(), UserResponse.from(user));
    }

    @Transactional(readOnly = true)
    public UserResponse me(CurrentUser currentUser) {
        return userRepository.findById(currentUser.id())
                .map(UserResponse::from)
                .orElseThrow(() -> new NotFoundException("User not found"));
    }
}
