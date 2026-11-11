package com.ledgerline.auth;

import com.ledgerline.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    @SecurityRequirements
    @Operation(summary = "Register a new holder",
            description = "Public, no token needed. Always creates a HOLDER; any role sent in the body is ignored. "
                    + "Email is trimmed and lowercased. Duplicate email returns 409.")
    public UserResponse register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request);
    }

    @PostMapping("/login")
    @SecurityRequirements
    @Operation(summary = "Log in and get a JWT",
            description = "Public, no token needed. Returns a bearer token and the user. Unknown email, wrong "
                    + "password and disabled account all return the same 401.")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request);
    }

    @GetMapping("/me")
    @Operation(summary = "Get the current user",
            description = "Any logged-in user: ADMIN, HOLDER or AUDITOR.")
    public UserResponse me(@AuthenticationPrincipal CurrentUser currentUser) {
        return authService.me(currentUser);
    }
}
