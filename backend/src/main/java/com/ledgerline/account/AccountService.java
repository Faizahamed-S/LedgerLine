package com.ledgerline.account;

import com.ledgerline.audit.AuditAction;
import com.ledgerline.audit.AuditService;
import com.ledgerline.common.ConflictException;
import com.ledgerline.common.NotFoundException;
import com.ledgerline.domain.Account;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.User;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.CurrentUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

@Service
public class AccountService {

    static final int MAX_ACCOUNTS_PER_HOLDER = 5;

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;
    private final AccountAccessPolicy accessPolicy;
    private final AuditService auditService;

    public AccountService(AccountRepository accountRepository,
                          UserRepository userRepository,
                          AccountAccessPolicy accessPolicy,
                          AuditService auditService) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
        this.auditService = auditService;
    }

    /** Locks the holder's user row so two concurrent requests cannot both pass the account limit. */
    @Transactional
    public AccountResponse requestAccount(CurrentUser user, CreateAccountRequest request) {
        User owner = userRepository.findByIdForUpdate(user.id())
                .orElseThrow(() -> new NotFoundException("User not found"));
        if (accountRepository.countByOwnerId(owner.getId()) >= MAX_ACCOUNTS_PER_HOLDER) {
            throw new ConflictException("A holder may have at most " + MAX_ACCOUNTS_PER_HOLDER + " accounts");
        }
        Account account = accountRepository.save(new Account(owner, request.name(), AccountType.CUSTOMER));
        auditService.record(user.id(), AuditAction.ACCOUNT_REQUESTED, AuditService.TARGET_ACCOUNT, account.getId(),
                Map.of("name", account.getName()));
        return AccountResponse.from(account);
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> listAccounts(CurrentUser user) {
        List<Account> accounts = accessPolicy.canViewAllAccounts(user)
                ? accountRepository.findAllByOrderByIdAsc()
                : accountRepository.findByOwnerIdOrderByIdAsc(user.id());
        return accounts.stream()
                .filter(account -> accessPolicy.canView(user, account))
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(CurrentUser user, Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException(AccountAccessPolicy.NOT_FOUND));
        accessPolicy.requireCanView(user, account);
        return AccountResponse.from(account);
    }
}
