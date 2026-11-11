package com.ledgerline.admin;

import com.ledgerline.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final AdminUserService adminUserService;

    public AdminUserController(AdminUserService adminUserService) {
        this.adminUserService = adminUserService;
    }

    @GetMapping
    @Operation(summary = "List all users",
            description = "ADMIN only. Returns every user without password hashes. Written to the audit log.")
    public List<AdminUserResponse> listUsers(@AuthenticationPrincipal CurrentUser admin) {
        return adminUserService.listUsers(admin);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Create a user with any role",
            description = "ADMIN only. Creates an ADMIN, HOLDER or AUDITOR. Duplicate email returns 409. "
                    + "Written to the audit log.")
    public AdminUserResponse createUser(@AuthenticationPrincipal CurrentUser admin,
                                        @Valid @RequestBody CreateUserRequest request) {
        return adminUserService.createUser(admin, request);
    }

    @PatchMapping("/{id}/status")
    @Operation(summary = "Enable or disable a user",
            description = "ADMIN only. Sets ACTIVE or DISABLED; takes effect on the user's next request. "
                    + "An admin cannot disable themselves (400). Written to the audit log.")
    public AdminUserResponse updateStatus(@AuthenticationPrincipal CurrentUser admin,
                                          @PathVariable Long id,
                                          @Valid @RequestBody UpdateUserStatusRequest request) {
        return adminUserService.updateStatus(admin, id, request);
    }
}
