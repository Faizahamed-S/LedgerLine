package com.ledgerline.anomaly;

import com.ledgerline.security.CurrentUser;
import io.swagger.v3.oas.annotations.Operation;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
public class AnomalyFlagController {

    private final FlagService flagService;

    public AnomalyFlagController(FlagService flagService) {
        this.flagService = flagService;
    }

    @GetMapping("/api/audit/flags")
    @PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
    @Operation(summary = "List anomaly flags",
            description = "ADMIN and AUDITOR only. Read-only. Rule-based anomaly flags (no ML/LLM), newest first, "
                    + "max 500. status=OPEN (not yet reviewed), REVIEWED, or omit for all. Each flag includes its "
                    + "review and the reviewer's email, if reviewed. Flags never move money.")
    public List<FlagResponse> list(@RequestParam(required = false) FlagStatus status) {
        return flagService.list(status);
    }

    @PostMapping("/api/admin/flags/{id}/review")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Review an anomaly flag",
            description = "ADMIN only. Records DISMISSED or CONFIRMED with a required note (max 500 characters). A "
                    + "flag can be reviewed once; a second review is 409. CONFIRMED does nothing automatically: no "
                    + "account is frozen and no money moves. To act, freeze the account separately via "
                    + "PATCH /api/admin/accounts/{id}/status. Written to the audit log.")
    public FlagResponse review(@AuthenticationPrincipal CurrentUser admin,
                               @PathVariable long id,
                               @Valid @RequestBody ReviewFlagRequest request) {
        return flagService.review(admin, id, request);
    }
}
