package com.ledgerline.bootstrap;

import com.ledgerline.audit.AuditAction;
import com.ledgerline.audit.AuditService;
import com.ledgerline.common.Emails;
import com.ledgerline.domain.Role;
import com.ledgerline.domain.User;
import com.ledgerline.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

/**
 * Creates the first ADMIN from environment variables. This is the only way to create an admin when none exists;
 * there is deliberately no HTTP endpoint for it.
 */
@Component
@ConditionalOnProperty(name = "ledgerline.bootstrap.enabled", havingValue = "true")
public class AdminBootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapRunner.class);

    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_BYTES = 72;
    private static final String ADMIN_FULL_NAME = "Administrator";

    private final BootstrapProperties properties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminBootstrapRunner(BootstrapProperties properties,
                                UserRepository userRepository,
                                PasswordEncoder passwordEncoder,
                                AuditService auditService) {
        this.properties = properties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByRole(Role.ADMIN)) {
            log.info("Admin already exists, bootstrap skipped");
            return;
        }

        String email = Emails.normalize(properties.email());
        String password = properties.password();

        if (email == null || email.isEmpty() || !email.contains("@")) {
            throw new IllegalStateException(
                    "Admin bootstrap is enabled but BOOTSTRAP_ADMIN_EMAIL is missing or not a valid email");
        }
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("Admin bootstrap is enabled but BOOTSTRAP_ADMIN_PASSWORD is missing "
                    + "or shorter than " + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalStateException(
                    "BOOTSTRAP_ADMIN_PASSWORD must not be longer than " + MAX_PASSWORD_BYTES + " bytes (BCrypt limit)");
        }
        if (userRepository.existsByEmail(email)) {
            throw new IllegalStateException("Admin bootstrap failed: a non-admin user with email " + email
                    + " already exists. Use a different BOOTSTRAP_ADMIN_EMAIL.");
        }

        User admin = userRepository.save(new User(email, passwordEncoder.encode(password), ADMIN_FULL_NAME, Role.ADMIN));
        auditService.record(null, AuditAction.ADMIN_BOOTSTRAPPED, AuditService.TARGET_USER, admin.getId(),
                Map.of("email", email));
        log.info("Bootstrap admin created: id={}, email={}", admin.getId(), email);
    }
}
