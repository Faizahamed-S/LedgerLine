package com.ledgerline.support;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Real Postgres and Redis, started once for every test class. The app connects as ledger_app and Flyway as
 * ledger_owner, exactly like production. @ServiceConnection is deliberately not used: it would connect the app as
 * the container superuser and the permission tests would prove nothing.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class IntegrationTestBase {

    protected static final String APP_USER = "ledger_app";
    protected static final String APP_PASSWORD = "app_test";
    protected static final String OWNER_USER = "ledger_owner";
    protected static final String OWNER_PASSWORD = "owner_test";

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16")
            .withInitScript("db/test-roles.sql");

    protected static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    private static final JdbcTemplate OWNER_JDBC;

    static {
        POSTGRES.start();
        REDIS.start();
        OWNER_JDBC = new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), OWNER_USER, OWNER_PASSWORD));
    }

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_USER);
        registry.add("spring.datasource.password", () -> APP_PASSWORD);
        registry.add("spring.flyway.user", () -> OWNER_USER);
        registry.add("spring.flyway.password", () -> OWNER_PASSWORD);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("ledgerline.bootstrap.enabled", () -> "true");
        registry.add("ledgerline.bootstrap.email", () -> Api.ADMIN_EMAIL);
        registry.add("ledgerline.bootstrap.password", () -> Api.ADMIN_PASSWORD);
    }

    @Autowired
    protected TestRestTemplate rest;

    /** The application's own DataSource, so it connects as ledger_app. */
    @Autowired
    protected JdbcTemplate jdbc;

    protected Api api;

    @BeforeEach
    void setUpApi() {
        api = new Api(rest);
    }

    /** A separate connection as ledger_owner, the role that owns the tables. */
    protected JdbcTemplate ownerJdbc() {
        return OWNER_JDBC;
    }

    protected long balanceOf(long accountId) {
        return jdbc.queryForObject("SELECT balance FROM accounts WHERE id = ?", Long.class, accountId);
    }

    protected long ledgerSum(long accountId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(amount), 0) FROM ledger_entries WHERE account_id = ?", Long.class, accountId);
    }

    protected Long latestBalanceAfter(long accountId) {
        List<Long> values = jdbc.queryForList(
                "SELECT balance_after FROM ledger_entries WHERE account_id = ? ORDER BY id DESC LIMIT 1",
                Long.class, accountId);
        return values.isEmpty() ? null : values.get(0);
    }

    protected long countTransactionsInvolving(long accountId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE from_account_id = ? OR to_account_id = ?",
                Long.class, accountId, accountId);
    }

    protected long countLedgerEntries(long accountId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM ledger_entries WHERE account_id = ?", Long.class, accountId);
    }

    protected long externalAccountId() {
        return jdbc.queryForObject("SELECT id FROM accounts WHERE type = 'EXTERNAL'", Long.class);
    }

    /**
     * Runs {@code tasks} copies of {@code task} on their own threads, all released at the same instant by a latch,
     * and returns their results in task order.
     */
    protected static <T> List<T> runConcurrently(int tasks, IntFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks);
        CountDownLatch ready = new CountDownLatch(tasks);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < tasks; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return task.apply(index);
                }));
            }
            if (!ready.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Worker threads did not start");
            }
            go.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
