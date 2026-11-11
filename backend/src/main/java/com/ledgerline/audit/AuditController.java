package com.ledgerline.audit;

import io.swagger.v3.oas.annotations.Operation;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/audit")
@PreAuthorize("hasAnyRole('ADMIN', 'AUDITOR')")
public class AuditController {

    private final AuditQueryService auditQueryService;
    private final ChainVerificationService chainVerificationService;

    public AuditController(AuditQueryService auditQueryService, ChainVerificationService chainVerificationService) {
        this.auditQueryService = auditQueryService;
        this.chainVerificationService = chainVerificationService;
    }

    @GetMapping("/chain")
    @Operation(summary = "Verify the ledger hash chain",
            description = "ADMIN and AUDITOR only. Read-only, moves no money and takes no locks. Re-walks every "
                    + "account's ledger entries in id order, recomputes each entry_hash and checks each prev_hash "
                    + "links to the previous entry. Any edited, inserted or deleted entry shows up in breaks, even if "
                    + "made directly in the database. Entries from before the chain existed are counted as "
                    + "unchainedLegacyEntries, not breaks.")
    public ChainVerificationResponse chain() {
        return chainVerificationService.verify();
    }

    @GetMapping("/log")
    @Operation(summary = "Read the audit log",
            description = "ADMIN and AUDITOR only. Read-only. Newest first; limit defaults to 100 and is capped at "
                    + "500. Covers admin and security actions (logins, user and account changes), not money "
                    + "movements. details is the stored JSON as a string.")
    public List<AuditLogResponse> log(@RequestParam(defaultValue = "100") int limit) {
        return auditQueryService.recentLog(limit);
    }

    @GetMapping("/reconciliation")
    @Operation(summary = "Check that the books balance",
            description = "ADMIN and AUDITOR only. Read-only, moves no money and takes no locks. balanced is true "
                    + "when all ledger entries sum to 0, customer balances equal minus the EXTERNAL balance, and "
                    + "every account's balance equals the sum of its ledger entries. Any account where it does not "
                    + "is listed in mismatchedAccounts.")
    public ReconciliationResponse reconciliation() {
        return auditQueryService.reconcile();
    }
}
