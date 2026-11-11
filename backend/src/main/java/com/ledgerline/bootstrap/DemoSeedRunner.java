package com.ledgerline.bootstrap;

import com.ledgerline.account.AccountResponse;
import com.ledgerline.account.AccountService;
import com.ledgerline.account.CreateAccountRequest;
import com.ledgerline.admin.AdminAccountService;
import com.ledgerline.admin.UpdateAccountStatusRequest;
import com.ledgerline.audit.AuditAction;
import com.ledgerline.audit.AuditService;
import com.ledgerline.common.Emails;
import com.ledgerline.domain.AccountStatus;
import com.ledgerline.domain.Role;
import com.ledgerline.domain.TransactionStatus;
import com.ledgerline.domain.User;
import com.ledgerline.money.MoneyResult;
import com.ledgerline.money.MoneyService;
import com.ledgerline.repository.UserRepository;
import com.ledgerline.security.CurrentUser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Demo data so there is one login per role. Runs after {@link AdminBootstrapRunner}.
 * <p>
 * Accounts are opened and approved through the account services, and every paisa moves through
 * {@link MoneyService} with fixed idempotency keys, so the seed produces real transactions, ledger entries and
 * audit rows, and the books balance. Everything runs in one transaction: a failure leaves no partial seed.
 */
@Component
@Order(DemoSeedRunner.ORDER)
@ConditionalOnProperty(name = "ledgerline.seed.enabled", havingValue = "true")
public class DemoSeedRunner implements ApplicationRunner {

    public static final int ORDER = AdminBootstrapRunner.ORDER + 1;

    public static final String HOLDER1_EMAIL = "holder1@ledgerline.demo";
    public static final String HOLDER2_EMAIL = "holder2@ledgerline.demo";
    public static final String AUDITOR_EMAIL = "auditor@ledgerline.demo";

    static final long HOLDER1_DEPOSIT_PAISE = 5_000_000;
    static final long HOLDER2_DEPOSIT_PAISE = 2_000_000;
    static final long TRANSFER_PAISE = 250_000;

    private static final Logger log = LoggerFactory.getLogger(DemoSeedRunner.class);
    private static final int MIN_PASSWORD_LENGTH = 12;
    private static final int MAX_PASSWORD_BYTES = 72;

    private final SeedProperties seedProperties;
    private final BootstrapProperties bootstrapProperties;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AccountService accountService;
    private final AdminAccountService adminAccountService;
    private final MoneyService moneyService;
    private final AuditService auditService;

    public DemoSeedRunner(SeedProperties seedProperties,
                          BootstrapProperties bootstrapProperties,
                          UserRepository userRepository,
                          PasswordEncoder passwordEncoder,
                          AccountService accountService,
                          AdminAccountService adminAccountService,
                          MoneyService moneyService,
                          AuditService auditService) {
        this.seedProperties = seedProperties;
        this.bootstrapProperties = bootstrapProperties;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.accountService = accountService;
        this.adminAccountService = adminAccountService;
        this.moneyService = moneyService;
        this.auditService = auditService;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (userRepository.existsByEmail(HOLDER1_EMAIL)) {
            log.info("Demo seed already present, skipped");
            return;
        }
        String password = requireValidPassword(seedProperties.password());
        CurrentUser admin = seedingAdmin();

        User holder1 = createUser(HOLDER1_EMAIL, "Holder One", Role.HOLDER, password);
        User holder2 = createUser(HOLDER2_EMAIL, "Holder Two", Role.HOLDER, password);
        User auditor = createUser(AUDITOR_EMAIL, "Demo Auditor", Role.AUDITOR, password);
        CurrentUser holder1User = asCurrentUser(holder1);
        CurrentUser holder2User = asCurrentUser(holder2);

        long account1 = openActiveAccount(holder1User, "Holder One Savings", admin);
        long account2 = openActiveAccount(holder2User, "Holder Two Current", admin);

        requirePosted("deposit for holder1",
                moneyService.deposit(admin, "seed-deposit-holder1", account1, HOLDER1_DEPOSIT_PAISE));
        requirePosted("deposit for holder2",
                moneyService.deposit(admin, "seed-deposit-holder2", account2, HOLDER2_DEPOSIT_PAISE));
        requirePosted("transfer holder1 -> holder2",
                moneyService.transfer(holder1User, "seed-transfer-holder1-holder2", account1, account2, TRANSFER_PAISE));

        auditService.record(admin.id(), AuditAction.DEMO_SEED_CREATED, null, null, Map.of(
                "users", List.of(HOLDER1_EMAIL, HOLDER2_EMAIL, AUDITOR_EMAIL),
                "accounts", List.of(account1, account2)));
        log.info("Demo seed created: {}, {}, {} (accounts {} and {})",
                HOLDER1_EMAIL, HOLDER2_EMAIL, auditor.getEmail(), account1, account2);
    }

    private static String requireValidPassword(String password) {
        if (password == null || password.length() < MIN_PASSWORD_LENGTH) {
            throw new IllegalStateException("Demo seed is enabled but SEED_DEMO_PASSWORD is missing or shorter than "
                    + MIN_PASSWORD_LENGTH + " characters");
        }
        if (password.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new IllegalStateException(
                    "SEED_DEMO_PASSWORD must not be longer than " + MAX_PASSWORD_BYTES + " bytes (BCrypt limit)");
        }
        return password;
    }

    /** The bootstrap admin if configured, otherwise the oldest ADMIN. */
    private CurrentUser seedingAdmin() {
        String bootstrapEmail = Emails.normalize(bootstrapProperties.email());
        User admin = (bootstrapEmail == null || bootstrapEmail.isEmpty()
                ? userRepository.findFirstByRoleOrderByIdAsc(Role.ADMIN)
                : userRepository.findByEmail(bootstrapEmail).filter(user -> user.getRole() == Role.ADMIN)
                        .or(() -> userRepository.findFirstByRoleOrderByIdAsc(Role.ADMIN)))
                .orElseThrow(() -> new IllegalStateException(
                        "Demo seed needs an ADMIN to approve accounts and deposit money; enable the admin bootstrap"));
        return asCurrentUser(admin);
    }

    private User createUser(String email, String fullName, Role role, String password) {
        return userRepository.save(new User(email, passwordEncoder.encode(password), fullName, role));
    }

    private long openActiveAccount(CurrentUser holder, String name, CurrentUser admin) {
        AccountResponse account = accountService.requestAccount(holder, new CreateAccountRequest(name));
        adminAccountService.updateStatus(admin, account.id(), new UpdateAccountStatusRequest(AccountStatus.ACTIVE));
        return account.id();
    }

    private static void requirePosted(String step, MoneyResult result) {
        if (result.body().status() != TransactionStatus.POSTED) {
            throw new IllegalStateException("Demo seed " + step + " was refused: " + result.body().rejectReason());
        }
    }

    private static CurrentUser asCurrentUser(User user) {
        return new CurrentUser(user.getId(), user.getEmail(), user.getRole());
    }
}
