package com.ledgerline.money;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerline.support.Api;
import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class IdempotencyTest extends IntegrationTestBase {

    @Test
    void sameKeyTwiceMovesMoneyOnceAndReplaysTheSameResponse() {
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);
        String key = Api.newKey();

        ResponseEntity<JsonNode> first = api.deposit(holder.token(), account, 500, key);
        ResponseEntity<JsonNode> second = api.deposit(holder.token(), account, 500, key);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(first.getHeaders().getFirst(Api.REPLAYED_HEADER)).isNull();
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(second.getHeaders().getFirst(Api.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(second.getBody()).isEqualTo(first.getBody());

        long transactionId = first.getBody().get("transactionId").asLong();
        assertThat(countTransactionsInvolving(account)).isEqualTo(1);
        assertThat(replayCount(holder, key)).isEqualTo(1);
        assertThat(linkedTransactionId(holder, key)).isEqualTo(transactionId);
        assertThat(balanceOf(account)).isEqualTo(500);
        assertThat(countLedgerEntries(account)).isEqualTo(1);
    }

    @Test
    void sameKeyFromTwentyThreadsMovesMoneyOnce() throws Exception {
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);
        String key = Api.newKey();

        List<ResponseEntity<JsonNode>> responses =
                runConcurrently(20, i -> api.deposit(holder.token(), account, 700, key));

        assertThat(responses).allSatisfy(r -> assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED));
        assertThat(responses.stream().map(r -> r.getBody().get("transactionId").asLong()).distinct())
                .hasSize(1);
        assertThat(responses.stream().filter(r -> "true".equals(r.getHeaders().getFirst(Api.REPLAYED_HEADER))))
                .hasSize(19);

        assertThat(countTransactionsInvolving(account)).isEqualTo(1);
        assertThat(replayCount(holder, key)).isEqualTo(19);
        assertThat(balanceOf(account)).isEqualTo(700);
        assertThat(ledgerSum(account)).isEqualTo(700);
    }

    @Test
    void sameKeyWithDifferentAmountIsRejectedWith409() {
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);
        String key = Api.newKey();

        assertThat(api.deposit(holder.token(), account, 500, key).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> reused = api.deposit(holder.token(), account, 600, key);

        assertThat(reused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(countTransactionsInvolving(account)).isEqualTo(1);
        assertThat(balanceOf(account)).isEqualTo(500);
        assertThat(replayCount(holder, key)).isZero();
    }

    @Test
    void sameKeyUsedByTwoDifferentUsersIsIndependent() {
        TestUser alice = api.newHolder();
        TestUser bob = api.newHolder();
        long a = api.openActiveAccount(alice);
        long b = api.openActiveAccount(bob);
        String key = Api.newKey();

        ResponseEntity<JsonNode> aliceDeposit = api.deposit(alice.token(), a, 300, key);
        ResponseEntity<JsonNode> bobDeposit = api.deposit(bob.token(), b, 400, key);

        assertThat(aliceDeposit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bobDeposit.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(bobDeposit.getHeaders().getFirst(Api.REPLAYED_HEADER)).isNull();
        assertThat(bobDeposit.getBody().get("transactionId").asLong())
                .isNotEqualTo(aliceDeposit.getBody().get("transactionId").asLong());

        assertThat(balanceOf(a)).isEqualTo(300);
        assertThat(balanceOf(b)).isEqualTo(400);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM idempotency_keys WHERE idempotency_key = ?", Long.class, key)).isEqualTo(2);
    }

    @Test
    void replayOfARejectedRequestStaysRejectedEvenAfterFundsArrive() {
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);
        String key = Api.newKey();

        ResponseEntity<JsonNode> original = api.withdraw(holder.token(), account, 1_000, key);
        assertThat(original.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(original.getBody().get("rejectReason").asText()).isEqualTo("INSUFFICIENT_FUNDS");

        assertThat(api.deposit(holder.token(), account, 5_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        ResponseEntity<JsonNode> replay = api.withdraw(holder.token(), account, 1_000, key);

        assertThat(replay.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(replay.getHeaders().getFirst(Api.REPLAYED_HEADER)).isEqualTo("true");
        assertThat(replay.getBody()).isEqualTo(original.getBody());

        assertThat(balanceOf(account)).isEqualTo(5_000);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE from_account_id = ? AND kind = 'WITHDRAWAL'",
                Long.class, account)).isEqualTo(1);
        assertThat(jdbc.queryForObject(
                "SELECT status FROM transactions WHERE id = ?", String.class,
                original.getBody().get("transactionId").asLong())).isEqualTo("REJECTED");
        assertThat(replayCount(holder, key)).isEqualTo(1);
    }

    private int replayCount(TestUser user, String key) {
        return jdbc.queryForObject(
                "SELECT replay_count FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, user.id(), key);
    }

    private Long linkedTransactionId(TestUser user, String key) {
        return jdbc.queryForObject(
                "SELECT transaction_id FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
                Long.class, user.id(), key);
    }
}
