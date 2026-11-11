package com.ledgerline.admin;

import com.ledgerline.audit.AuditAction;
import com.ledgerline.audit.AuditService;
import com.ledgerline.common.BadRequestException;
import com.ledgerline.common.ConflictException;
import com.ledgerline.common.NotFoundException;
import com.ledgerline.domain.User;
import com.ledgerline.domain.UserStatus;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.CurrentUser;
import org.springframework.data.domain.Sort;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AdminUserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public AdminUserService(UserRepository userRepository,
                            PasswordEncoder passwordEncoder,
                            AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional
    public List<AdminUserResponse> listUsers(CurrentUser admin) {
        List<AdminUserResponse> users = userRepository.findAll(Sort.by("id")).stream()
                .map(AdminUserResponse::from)
                .toList();
        auditService.record(admin.id(), AuditAction.ADMIN_USERS_LISTED, null, null, Map.of("count", users.size()));
        return users;
    }

    @Transactional
    public AdminUserResponse createUser(CurrentUser admin, CreateUserRequest request) {
        if (userRepository.existsByEmail(request.email())) {
            throw new ConflictException("Email is already registered");
        }
        User user = userRepository.save(new User(
                request.email(), passwordEncoder.encode(request.password()), request.fullName(), request.role()));
        auditService.record(admin.id(), AuditAction.ADMIN_USER_CREATED, AuditService.TARGET_USER, user.getId(),
                Map.of("email", user.getEmail(), "role", user.getRole().name()));
        return AdminUserResponse.from(user);
    }

    @Transactional
    public AdminUserResponse updateStatus(CurrentUser admin, Long userId, UpdateUserStatusRequest request) {
        if (admin.id().equals(userId) && request.status() == UserStatus.DISABLED) {
            throw new BadRequestException("Admins cannot disable their own account");
        }
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new NotFoundException("User not found"));
        UserStatus previous = user.getStatus();
        user.setStatus(request.status());
        auditService.record(admin.id(), AuditAction.ADMIN_USER_STATUS_CHANGED, AuditService.TARGET_USER, user.getId(),
                Map.of("from", previous.name(), "to", request.status().name()));
        return AdminUserResponse.from(user);
    }
}
