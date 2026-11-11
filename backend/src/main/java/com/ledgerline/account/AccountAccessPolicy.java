package com.ledgerline.account;

import com.ledgerline.common.ConflictException;
import com.ledgerline.common.ForbiddenException;
import com.ledgerline.common.NotFoundException;
import com.ledgerline.domain.Account;
import com.ledgerline.domain.AccountStatus;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.Role;
import com.ledgerline.security.CurrentUser;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The single source of truth for who may see or act on an account. See docs/ROLES.md.
 * <ul>
 *   <li>Not allowed to view: 404, so the account's existence is not revealed.</li>
 *   <li>Allowed to view but not to act: 403.</li>
 *   <li>Allowed to act but the account is not ACTIVE: 409.</li>
 * </ul>
 * Checks run in that order. The {@code check...} methods return the refusal so money requests can record it;
 * the {@code requireCan...} methods throw it.
 */
@Component
public class AccountAccessPolicy {

    public static final String NOT_FOUND = "Account not found";
    public static final String FORBIDDEN = "You are not allowed to perform this action on this account";
    public static final String NOT_ACTIVE = "Account is not active";

    public boolean canViewAllAccounts(CurrentUser user) {
        return user.role() == Role.ADMIN || user.role() == Role.AUDITOR;
    }

    public boolean canView(CurrentUser user, Account account) {
        return switch (user.role()) {
            case ADMIN, AUDITOR -> true;
            case HOLDER -> isOwnCustomerAccount(user, account);
        };
    }

    public void requireCanView(CurrentUser user, Account account) {
        if (!canView(user, account)) {
            throw new NotFoundException(NOT_FOUND);
        }
    }

    /** The status a NOT_AUTHORISED refusal on this account gets for this user. */
    public HttpStatus notAuthorisedStatus(CurrentUser user, Account account) {
        return canView(user, account) ? HttpStatus.FORBIDDEN : HttpStatus.NOT_FOUND;
    }

    /** HOLDER: own ACTIVE accounts. ADMIN: any ACTIVE CUSTOMER account. AUDITOR: never. */
    public Optional<Refusal> checkDeposit(CurrentUser user, Account account) {
        if (!canView(user, account)) {
            return Optional.of(Refusal.notAuthorised(HttpStatus.NOT_FOUND));
        }
        boolean roleMayDeposit = user.role() == Role.HOLDER || user.role() == Role.ADMIN;
        if (!isCustomer(account) || !roleMayDeposit) {
            return Optional.of(Refusal.notAuthorised(HttpStatus.FORBIDDEN));
        }
        return checkActive(account);
    }

    /** Withdraw or transfer out. Only the HOLDER who owns the account; never ADMIN or AUDITOR. */
    public Optional<Refusal> checkDebit(CurrentUser user, Account account) {
        if (!canView(user, account)) {
            return Optional.of(Refusal.notAuthorised(HttpStatus.NOT_FOUND));
        }
        if (!isCustomer(account) || user.role() != Role.HOLDER) {
            return Optional.of(Refusal.notAuthorised(HttpStatus.FORBIDDEN));
        }
        return checkActive(account);
    }

    /**
     * Transfer destination: any ACTIVE CUSTOMER account, including ones the sender cannot view.
     * Missing, EXTERNAL, PENDING and FROZEN destinations all get the same 404 so none of them can be told apart.
     */
    public Optional<Refusal> checkReceive(Account account) {
        if (account == null || !isCustomer(account) || account.getStatus() != AccountStatus.ACTIVE) {
            return Optional.of(Refusal.destinationUnavailable());
        }
        return Optional.empty();
    }

    public void requireCanDeposit(CurrentUser user, Account account) {
        throwIfRefused(checkDeposit(user, account));
    }

    public void requireCanDebit(CurrentUser user, Account account) {
        throwIfRefused(checkDebit(user, account));
    }

    public void requireCanReceive(Account account) {
        throwIfRefused(checkReceive(account));
    }

    /** Approve / freeze / unfreeze. ADMIN only, never on the EXTERNAL account. */
    public void requireCanChangeStatus(CurrentUser user, Account account) {
        requireCanView(user, account);
        if (!isCustomer(account) || user.role() != Role.ADMIN) {
            throw new ForbiddenException(FORBIDDEN);
        }
    }

    private static boolean isCustomer(Account account) {
        return account.getType() == AccountType.CUSTOMER;
    }

    private static boolean isOwnCustomerAccount(CurrentUser user, Account account) {
        return isCustomer(account)
                && account.getOwner() != null
                && account.getOwner().getId().equals(user.id());
    }

    private static Optional<Refusal> checkActive(Account account) {
        return account.getStatus() == AccountStatus.ACTIVE
                ? Optional.empty()
                : Optional.of(Refusal.accountNotActive());
    }

    private static void throwIfRefused(Optional<Refusal> refusal) {
        if (refusal.isEmpty()) {
            return;
        }
        Refusal r = refusal.get();
        throw switch (r.status()) {
            case NOT_FOUND -> new NotFoundException(r.message());
            case FORBIDDEN -> new ForbiddenException(r.message());
            case CONFLICT -> new ConflictException(r.message());
            default -> new IllegalStateException("Unexpected refusal status " + r.status());
        };
    }
}
