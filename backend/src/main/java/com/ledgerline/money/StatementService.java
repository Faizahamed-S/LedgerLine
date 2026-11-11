package com.ledgerline.money;

import com.ledgerline.account.AccountAccessPolicy;
import com.ledgerline.common.NotFoundException;
import com.ledgerline.domain.Account;
import com.ledgerline.domain.LedgerEntry;
import com.ledgerline.domain.LedgerTransaction;
import com.ledgerline.domain.Role;
import com.ledgerline.domain.TransactionKind;
import com.ledgerline.domain.TransactionStatus;
import com.ledgerline.domain.User;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerEntryRepository;
import com.ledgerline.repository.LedgerTransactionRepository;
import com.ledgerline.security.CurrentUser;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@Service
public class StatementService {

    static final int MAX_LINES = 500;
    static final String ANOTHER_USER = "another user";

    private static final Comparator<StatementLine> NEWEST_FIRST = Comparator
            .comparing(StatementLine::createdAt)
            .thenComparing(StatementLine::transactionId)
            .reversed();

    private final AccountRepository accountRepository;
    private final LedgerEntryRepository entryRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final AccountAccessPolicy accessPolicy;

    public StatementService(AccountRepository accountRepository,
                            LedgerEntryRepository entryRepository,
                            LedgerTransactionRepository transactionRepository,
                            AccountAccessPolicy accessPolicy) {
        this.accountRepository = accountRepository;
        this.entryRepository = entryRepository;
        this.transactionRepository = transactionRepository;
        this.accessPolicy = accessPolicy;
    }

    @Transactional(readOnly = true)
    public StatementResponse statement(CurrentUser user, Long accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new NotFoundException(AccountAccessPolicy.NOT_FOUND));
        accessPolicy.requireCanView(user, account);

        Pageable newest = PageRequest.of(0, MAX_LINES);
        Stream<StatementLine> posted = entryRepository.findStatementEntries(accountId, newest).stream()
                .map(entry -> postedLine(user, accountId, entry));
        Stream<StatementLine> refused = transactionRepository
                .findForAccountByStatus(accountId, TransactionStatus.REJECTED, newest).stream()
                .map(transaction -> refusedLine(user, accountId, transaction));

        List<StatementLine> lines = Stream.concat(posted, refused)
                .sorted(NEWEST_FIRST)
                .limit(MAX_LINES)
                .toList();
        return new StatementResponse(account.getId(), account.getBalance(), lines);
    }

    private static StatementLine postedLine(CurrentUser viewer, Long accountId, LedgerEntry entry) {
        LedgerTransaction transaction = entry.getTransaction();
        return new StatementLine(
                transaction.getCreatedAt(),
                transaction.getId(),
                transaction.getKind(),
                entry.getAmount() < 0 ? StatementDirection.DEBIT : StatementDirection.CREDIT,
                Math.abs(entry.getAmount()),
                entry.getBalanceAfter(),
                counterparty(accountId, transaction),
                initiatorEmailFor(viewer, transaction),
                transaction.getStatus(),
                null);
    }

    /**
     * balance_at_decision belongs to one account only (the target of a deposit, otherwise the source). On the
     * other account's statement it is hidden, so a transfer recipient never sees the sender's balance.
     */
    private static StatementLine refusedLine(CurrentUser viewer, Long accountId, LedgerTransaction transaction) {
        Account balanceOwner = transaction.getKind() == TransactionKind.DEPOSIT
                ? transaction.getToAccount()
                : transaction.getFromAccount();
        boolean ownBalance = balanceOwner != null && Objects.equals(balanceOwner.getId(), accountId);
        return new StatementLine(
                transaction.getCreatedAt(),
                transaction.getId(),
                transaction.getKind(),
                StatementDirection.REFUSED,
                transaction.getAmount(),
                ownBalance ? transaction.getBalanceAtDecision() : null,
                counterparty(accountId, transaction),
                initiatorEmailFor(viewer, transaction),
                transaction.getStatus(),
                transaction.getRejectReason());
    }

    /** Holders only learn their own email; ADMIN and AUDITOR always see who initiated the line. */
    private static String initiatorEmailFor(CurrentUser viewer, LedgerTransaction transaction) {
        User initiator = transaction.getInitiatedBy();
        if (viewer.role() == Role.HOLDER && !initiator.getId().equals(viewer.id())) {
            return ANOTHER_USER;
        }
        return initiator.getEmail();
    }

    private static Long counterparty(Long accountId, LedgerTransaction transaction) {
        Account from = transaction.getFromAccount();
        Account to = transaction.getToAccount();
        boolean isFrom = from != null && Objects.equals(from.getId(), accountId);
        Account other = isFrom ? to : from;
        return other == null ? null : other.getId();
    }
}
