package com.ledgerline.money;

import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

class ConservationTest extends IntegrationTestBase {

    private static final int HOLDERS = 5;
    private static final int THREADS = 10;
    private static final int OPS_PER_THREAD = 20;
    private static final long INITIAL_DEPOSIT = 100_000;

    @Test
    void randomConcurrentTrafficConservesMoney() throws Exception {
        TestUser[] holders = new TestUser[HOLDERS];
        long[] accounts = new long[HOLDERS];
        for (int i = 0; i < HOLDERS; i++) {
            holders[i] = api.newHolder();
            accounts[i] = api.openActiveAccount(holders[i]);
            assertThat(api.deposit(holders[i].token(), accounts[i], INITIAL_DEPOSIT).getStatusCode())
                    .isEqualTo(HttpStatus.CREATED);
        }

        List<List<Integer>> statusesPerThread = runConcurrently(THREADS, thread -> {
            Random random = new Random(thread);
            List<Integer> statuses = new ArrayList<>();
            for (int op = 0; op < OPS_PER_THREAD; op++) {
                int h = random.nextInt(HOLDERS);
                long amount = 1 + random.nextInt(60_000);
                int status = switch (random.nextInt(3)) {
                    case 0 -> api.deposit(holders[h].token(), accounts[h], amount).getStatusCode().value();
                    case 1 -> api.withdraw(holders[h].token(), accounts[h], amount).getStatusCode().value();
                    default -> {
                        int other = (h + 1 + random.nextInt(HOLDERS - 1)) % HOLDERS;
                        yield api.transfer(holders[h].token(), accounts[h], accounts[other], amount)
                                .getStatusCode().value();
                    }
                };
                statuses.add(status);
            }
            return statuses;
        });

        List<Integer> statuses = statusesPerThread.stream().flatMap(List::stream).toList();
        assertThat(statuses).hasSize(THREADS * OPS_PER_THREAD);
        assertThat(statuses).allSatisfy(status -> assertThat(status).isIn(201, 422));
        assertThat(statuses).contains(201);

        assertThat(jdbc.queryForObject("SELECT COALESCE(SUM(amount), 0) FROM ledger_entries", Long.class))
                .isZero();

        List<Map<String, Object>> driftingAccounts = jdbc.queryForList("""
                SELECT a.id, a.balance, COALESCE(SUM(e.amount), 0) AS ledger_sum
                FROM accounts a
                LEFT JOIN ledger_entries e ON e.account_id = a.id
                GROUP BY a.id, a.balance
                HAVING a.balance <> COALESCE(SUM(e.amount), 0)
                """);
        assertThat(driftingAccounts).isEmpty();

        List<Map<String, Object>> unbalancedTransactions = jdbc.queryForList("""
                SELECT t.id, COUNT(e.id) AS entries, COALESCE(SUM(e.amount), 0) AS total
                FROM transactions t
                LEFT JOIN ledger_entries e ON e.transaction_id = t.id
                WHERE t.status = 'POSTED'
                GROUP BY t.id
                HAVING COUNT(e.id) <> 2 OR COALESCE(SUM(e.amount), 0) <> 0
                """);
        assertThat(unbalancedTransactions).isEmpty();

        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM accounts WHERE type = 'CUSTOMER' AND balance < 0", Long.class)).isZero();

        long customerTotal = jdbc.queryForObject(
                "SELECT COALESCE(SUM(balance), 0) FROM accounts WHERE type = 'CUSTOMER'", Long.class);
        assertThat(customerTotal).isEqualTo(-balanceOf(externalAccountId()));
    }
}
