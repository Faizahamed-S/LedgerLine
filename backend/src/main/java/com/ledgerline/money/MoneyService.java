package com.ledgerline.money;

import com.ledgerline.account.AccountAccessPolicy;
import com.ledgerline.account.Refusal;
import com.ledgerline.common.BadRequestException;
import com.ledgerline.common.ConflictException;
import com.ledgerline.common.NotFoundException;
import com.ledgerline.domain.Account;
import com.ledgerline.domain.AccountType;
import com.ledgerline.domain.LedgerEntry;
import com.ledgerline.domain.LedgerTransaction;
import com.ledgerline.domain.TransactionKind;
import com.ledgerline.domain.TransactionStatus;
import com.ledgerline.domain.User;
import com.ledgerline.repository.AccountRepository;
import com.ledgerline.repository.LedgerEntryRepository;
import com.ledgerline.repository.LedgerTransactionRepository;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.CurrentUser;
import jakarta.persistence.EntityManager;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.LongStream;

/**
 * Every money movement. Each public method is one READ COMMITTED transaction that:
 * (a) hashes the request, (b) claims the idempotency key or replays the stored result,
 * (c) row-locks the accounts in ascending id order, (d) decides under those locks,
 * (e/f) records a REJECTED row or posts the double entry, (g) links the key to the transaction.
 * The account row locks are the only concurrency control.
 */
@Service
public class MoneyService {

    private static final String CLAIM_KEY_SQL = """
            INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash)
            VALUES (?, ?, ?)
            ON CONFLICT (user_id, idempotency_key) DO NOTHING
            RETURNING id
            """;
    private static final String FIND_KEY_SQL =
            "SELECT id, request_hash, transaction_id FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?";
    private static final String MARK_REPLAYED_SQL =
            "UPDATE idempotency_keys SET replay_count = replay_count + 1, last_replayed_at = now() WHERE id = ?";
    private static final String LINK_TRANSACTION_SQL =
            "UPDATE idempotency_keys SET transaction_id = ? WHERE id = ?";

    private final JdbcTemplate jdbcTemplate;
    private final EntityManager entityManager;
    private final AccountRepository accountRepository;
    private final LedgerTransactionRepository transactionRepository;
    private final LedgerEntryRepository entryRepository;
    private final UserRepository userRepository;
    private final AccountAccessPolicy accessPolicy;

    public MoneyService(JdbcTemplate jdbcTemplate,
                        EntityManager entityManager,
                        AccountRepository accountRepository,
                        LedgerTransactionRepository transactionRepository,
                        LedgerEntryRepository entryRepository,
                        UserRepository userRepository,
                        AccountAccessPolicy accessPolicy) {
        this.jdbcTemplate = jdbcTemplate;
        this.entityManager = entityManager;
        this.accountRepository = accountRepository;
        this.transactionRepository = transactionRepository;
        this.entryRepository = entryRepository;
        this.userRepository = userRepository;
        this.accessPolicy = accessPolicy;
    }

    @Transactional
    public MoneyResult deposit(CurrentUser user, String idempotencyKey, long accountId, long amount) {
        long externalId = externalAccountId();
        String hash = requestHash(TransactionKind.DEPOSIT, externalId, accountId, amount);
        Long keyId = claimKey(user, idempotencyKey, hash);
        if (keyId == null) {
            return replay(user, idempotencyKey, hash);
        }

        Map<Long, Account> locked = lockInAscendingIdOrder(externalId, accountId);
        Account external = requireLocked(locked, externalId);
        Account target = requireLocked(locked, accountId);

        Optional<Refusal> refusal = accessPolicy.checkDeposit(user, target);
        return decideAndRecord(user, keyId, TransactionKind.DEPOSIT, external, target, target, amount, refusal);
    }

    @Transactional
    public MoneyResult withdraw(CurrentUser user, String idempotencyKey, long accountId, long amount) {
        long externalId = externalAccountId();
        String hash = requestHash(TransactionKind.WITHDRAWAL, accountId, externalId, amount);
        Long keyId = claimKey(user, idempotencyKey, hash);
        if (keyId == null) {
            return replay(user, idempotencyKey, hash);
        }

        Map<Long, Account> locked = lockInAscendingIdOrder(accountId, externalId);
        Account source = requireLocked(locked, accountId);
        Account external = requireLocked(locked, externalId);

        Optional<Refusal> refusal = accessPolicy.checkDebit(user, source);
        return decideAndRecord(user, keyId, TransactionKind.WITHDRAWAL, source, external, source, amount, refusal);
    }

    @Transactional
    public MoneyResult transfer(CurrentUser user, String idempotencyKey, long fromAccountId, long toAccountId,
                                long amount) {
        if (fromAccountId == toAccountId) {
            throw new BadRequestException("fromAccountId and toAccountId must be different");
        }
        String hash = requestHash(TransactionKind.TRANSFER, fromAccountId, toAccountId, amount);
        Long keyId = claimKey(user, idempotencyKey, hash);
        if (keyId == null) {
            return replay(user, idempotencyKey, hash);
        }

        Map<Long, Account> locked = lockInAscendingIdOrder(fromAccountId, toAccountId);
        Account from = requireLocked(locked, fromAccountId);
        Account to = locked.get(toAccountId);

        Optional<Refusal> refusal = accessPolicy.checkDebit(user, from)
                .or(() -> accessPolicy.checkReceive(to));
        // An unavailable destination is never stored, so the refused row and the response look the same whether
        // the destination is missing, EXTERNAL, PENDING or FROZEN.
        Account recordedTo = accessPolicy.checkReceive(to).isPresent() ? null : to;
        return decideAndRecord(user, keyId, TransactionKind.TRANSFER, from, recordedTo, from, amount, refusal);
    }

    /** Steps (d) funds check, (e) or (f), and (g). {@code balanceSubject} is whose balance a refusal records. */
    private MoneyResult decideAndRecord(CurrentUser user, long keyId, TransactionKind kind,
                                        Account from, Account to, Account balanceSubject, long amount,
                                        Optional<Refusal> permissionRefusal) {
        Optional<Refusal> refusal = permissionRefusal.or(() -> checkFunds(from, amount));
        User initiator = userRepository.getReferenceById(user.id());

        LedgerTransaction transaction;
        HttpStatus status;
        if (refusal.isPresent()) {
            transaction = transactionRepository.save(LedgerTransaction.rejected(
                    kind, amount, from, to, initiator, refusal.get().reason(), balanceSubject.getBalance()));
            status = refusal.get().status();
        } else {
            long fromBalanceAfter = Math.subtractExact(from.getBalance(), amount);
            long toBalanceAfter = Math.addExact(to.getBalance(), amount);
            from.setBalance(fromBalanceAfter);
            to.setBalance(toBalanceAfter);
            transaction = transactionRepository.save(LedgerTransaction.posted(kind, amount, from, to, initiator));
            entryRepository.save(new LedgerEntry(transaction, from, -amount, fromBalanceAfter));
            entryRepository.save(new LedgerEntry(transaction, to, amount, toBalanceAfter));
            status = HttpStatus.CREATED;
        }

        entityManager.flush();
        jdbcTemplate.update(LINK_TRANSACTION_SQL, transaction.getId(), keyId);
        return new MoneyResult(TransactionResponse.from(transaction), status, false);
    }

    private static Optional<Refusal> checkFunds(Account source, long amount) {
        if (source.getType() == AccountType.CUSTOMER && source.getBalance() < amount) {
            return Optional.of(Refusal.insufficientFunds());
        }
        return Optional.empty();
    }

    /**
     * Returns the new key row's id, or null if the key already exists. If another transaction holding the same
     * key is still in flight, Postgres blocks this INSERT until that transaction commits or rolls back.
     */
    private Long claimKey(CurrentUser user, String idempotencyKey, String requestHash) {
        List<Long> ids = jdbcTemplate.query(CLAIM_KEY_SQL, (rs, rowNum) -> rs.getLong(1),
                user.id(), idempotencyKey, requestHash);
        return ids.isEmpty() ? null : ids.get(0);
    }

    private MoneyResult replay(CurrentUser user, String idempotencyKey, String requestHash) {
        StoredKey stored = jdbcTemplate.queryForObject(FIND_KEY_SQL,
                (rs, rowNum) -> new StoredKey(
                        rs.getLong("id"),
                        rs.getString("request_hash"),
                        rs.getObject("transaction_id", Long.class)),
                user.id(), idempotencyKey);
        if (!stored.requestHash().equals(requestHash)) {
            throw new ConflictException("Idempotency key reused with a different request");
        }
        jdbcTemplate.update(MARK_REPLAYED_SQL, stored.id());

        if (stored.transactionId() == null) {
            throw new IllegalStateException("Idempotency key " + stored.id() + " has no transaction");
        }
        LedgerTransaction transaction = transactionRepository.findById(stored.transactionId())
                .orElseThrow(() -> new IllegalStateException("Transaction " + stored.transactionId() + " missing"));
        return new MoneyResult(TransactionResponse.from(transaction), replayStatus(user, transaction), true);
    }

    /**
     * Recomputes the original HTTP status from the stored row. NOT_AUTHORISED is 404 or 403 depending on whether
     * the caller can view the account; account ownership and type never change, so this matches the original
     * unless the caller's role was changed in between.
     */
    private HttpStatus replayStatus(CurrentUser user, LedgerTransaction transaction) {
        if (transaction.getStatus() == TransactionStatus.POSTED) {
            return HttpStatus.CREATED;
        }
        return switch (transaction.getRejectReason()) {
            case Refusal.NOT_AUTHORISED -> accessPolicy.notAuthorisedStatus(user, permissionSubject(transaction));
            case Refusal.ACCOUNT_NOT_ACTIVE -> Refusal.accountNotActive().status();
            case Refusal.DESTINATION_UNAVAILABLE -> Refusal.destinationUnavailable().status();
            case Refusal.INSUFFICIENT_FUNDS -> Refusal.insufficientFunds().status();
            default -> throw new IllegalStateException("Unknown reject reason " + transaction.getRejectReason());
        };
    }

    /** The account whose permission check produced a NOT_AUTHORISED refusal. */
    private static Account permissionSubject(LedgerTransaction transaction) {
        return transaction.getKind() == TransactionKind.DEPOSIT
                ? transaction.getToAccount()
                : transaction.getFromAccount();
    }

    private Map<Long, Account> lockInAscendingIdOrder(long... accountIds) {
        Map<Long, Account> locked = new HashMap<>();
        for (long id : LongStream.of(accountIds).distinct().sorted().toArray()) {
            accountRepository.findByIdForUpdate(id).ifPresent(account -> locked.put(id, account));
        }
        return locked;
    }

    private static Account requireLocked(Map<Long, Account> locked, long accountId) {
        Account account = locked.get(accountId);
        if (account == null) {
            throw new NotFoundException(AccountAccessPolicy.NOT_FOUND);
        }
        return account;
    }

    private long externalAccountId() {
        return accountRepository.findIdByType(AccountType.EXTERNAL)
                .orElseThrow(() -> new IllegalStateException("The EXTERNAL account is missing"));
    }

    static String requestHash(TransactionKind kind, long fromAccountId, long toAccountId, long amount) {
        String canonical = kind + "|" + fromAccountId + "|" + toAccountId + "|" + amount;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private record StoredKey(long id, String requestHash, Long transactionId) {
    }
}
