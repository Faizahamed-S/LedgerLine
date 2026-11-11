package com.ledgerline.admin;

import com.ledgerline.account.AccountAccessPolicy;
import com.ledgerline.account.AccountResponse;
import com.ledgerline.audit.AuditAction;
import com.ledgerline.audit.AuditService;
import com.ledgerline.common.BadRequestException;
import com.ledgerline.common.NotFoundException;
import com.ledgerline.domain.Account;
import com.ledgerline.domain.AccountStatus;
import com.ledgerline.domain.AccountType;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AdminAccountService {

    private final AccountRepository accountRepository;
    private final AccountAccessPolicy accessPolicy;
    private final AuditService auditService;

    public AdminAccountService(AccountRepository accountRepository,
                               AccountAccessPolicy accessPolicy,
                               AuditService auditService) {
        this.accountRepository = accountRepository;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listCustomerAccounts(AccountStatus status) {
        return accountRepository.findByStatusAndTypeOrderByIdAsc(status, AccountType.CUSTOMER).stream()
                .map(AccountResponse::from)
                .toList();
    }

    /**
     * Holds a row lock on the account for the whole transaction, so a status change and a money movement on the
     * same account are serialized.
     */
    @Transactional
    public AccountResponse updateStatus(CurrentUser admin, Long accountId, UpdateAccountStatusRequest request) {
        Account account = accountRepository.findByIdForUpdate(accountId)
                .orElseThrow(() -> new NotFoundException(AccountAccessPolicy.NOT_FOUND));
        accessPolicy.requireCanChangeStatus(admin, account);

        AccountStatus from = account.getStatus();
        AccountStatus to = request.status();
        AuditAction action = transitionAction(from, to);

        account.setStatus(to);
        auditService.record(admin.id(), action, AuditService.TARGET_ACCOUNT, account.getId(),
                Map.of("from", from.name(), "to", to.name()));
        return AccountResponse.from(account);
    }

    private static AuditAction transitionAction(AccountStatus from, AccountStatus to) {
        if (from == AccountStatus.PENDING && to == AccountStatus.ACTIVE) {
            return AuditAction.ACCOUNT_APPROVED;
        }
        if (from == AccountStatus.ACTIVE && to == AccountStatus.FROZEN) {
            return AuditAction.ACCOUNT_FROZEN;
        }
        if (from == AccountStatus.FROZEN && to == AccountStatus.ACTIVE) {
            return AuditAction.ACCOUNT_UNFROZEN;
        }
        throw new BadRequestException("Invalid status transition from " + from + " to " + to);
    }
}
