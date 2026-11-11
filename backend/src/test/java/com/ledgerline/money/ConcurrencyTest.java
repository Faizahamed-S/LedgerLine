package com.ledgerline.money;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrencyTest extends IntegrationTestBase {

    @Test
    void concurrentWithdrawalsNeverOverdrawAndNeverLoseAnUpdate() throws Exception {
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);
        assertThat(api.deposit(holder.token(), account, 10_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<ResponseEntity<JsonNode>> responses =
                runConcurrently(50, i -> api.withdraw(holder.token(), account, 3_000));

        long posted = responses.stream()
                .filter(r -> r.getStatusCode().value() == HttpStatus.CREATED.value())
                .filter(r -> "POSTED".equals(r.getBody().get("status").asText()))
                .count();
        long insufficientFunds = responses.stream()
                .filter(r -> r.getStatusCode().value() == HttpStatus.UNPROCESSABLE_ENTITY.value())
                .filter(r -> "INSUFFICIENT_FUNDS".equals(r.getBody().get("rejectReason").asText()))
                .count();
        assertThat(posted).isEqualTo(3);
        assertThat(insufficientFunds).isEqualTo(47);

        assertThat(balanceOf(account)).isEqualTo(1_000);
        assertThat(ledgerSum(account)).isEqualTo(balanceOf(account));
        assertThat(latestBalanceAfter(account)).isEqualTo(balanceOf(account));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE from_account_id = ? AND kind = 'WITHDRAWAL' AND status = ?",
                Long.class, account, "POSTED")).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE from_account_id = ? AND kind = 'WITHDRAWAL' AND status = ?",
                Long.class, account, "REJECTED")).isEqualTo(47);
    }

    @Test
    void opposingTransfersDoNotDeadlockAndConserveMoney() throws Exception {
        TestUser alice = api.newHolder();
        TestUser bob = api.newHolder();
        long a = api.openActiveAccount(alice);
        long b = api.openActiveAccount(bob);
        assertThat(api.deposit(alice.token(), a, 10_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(api.deposit(bob.token(), b, 10_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long totalBefore = balanceOf(a) + balanceOf(b);

        long started = System.nanoTime();
        List<ResponseEntity<JsonNode>> responses = runConcurrently(100, i -> i < 50
                ? api.transfer(alice.token(), a, b, 100)
                : api.transfer(bob.token(), b, a, 100));
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(elapsed).isLessThan(Duration.ofSeconds(30));
        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));

        assertThat(balanceOf(a) + balanceOf(b)).isEqualTo(totalBefore);
        assertThat(balanceOf(a)).isEqualTo(10_000);
        assertThat(balanceOf(b)).isEqualTo(10_000);
        assertThat(ledgerSum(a)).isEqualTo(balanceOf(a));
        assertThat(ledgerSum(b)).isEqualTo(balanceOf(b));
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE kind = 'TRANSFER' AND status = 'POSTED' "
                        + "AND ((from_account_id = ? AND to_account_id = ?) OR (from_account_id = ? AND to_account_id = ?))",
                Long.class, a, b, b, a)).isEqualTo(100);
    }
}
