package com.ledgerline.money;

import com.fasterxml.jackson.databind.JsonNode;
import com.ledgerline.support.Api;
import com.ledgerline.support.Api.TestUser;
import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorisationTest extends IntegrationTestBase {

    @Test
    void holderCannotWithdrawFromSomeoneElsesAccount() {
        TestUser alice = api.newHolder();
        TestUser bob = api.newHolder();
        long aliceAccount = api.openActiveAccount(alice);
        assertThat(api.deposit(alice.token(), aliceAccount, 5_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> response = api.withdraw(bob.token(), aliceAccount, 1_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balanceOf(aliceAccount)).isEqualTo(5_000);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE from_account_id = ? AND initiated_by = ? "
                        + "AND status = 'REJECTED' AND reject_reason = 'NOT_AUTHORISED'",
                Long.class, aliceAccount, bob.id())).isEqualTo(1);

        ResponseEntity<JsonNode> statement = api.statement(alice.token(), aliceAccount);
        assertThat(statement.getStatusCode()).isEqualTo(HttpStatus.OK);
        JsonNode refused = StreamSupport.stream(statement.getBody().get("lines").spliterator(), false)
                .filter(line -> "REFUSED".equals(line.get("direction").asText()))
                .findFirst()
                .orElseThrow();
        assertThat(refused.get("initiatedByEmail").asText()).isEqualTo("another user");
        assertThat(refused.get("rejectReason").asText()).isEqualTo("NOT_AUTHORISED");
    }

    @Test
    void holderCannotTransferOutOfSomeoneElsesAccount() {
        TestUser alice = api.newHolder();
        TestUser bob = api.newHolder();
        long aliceAccount = api.openActiveAccount(alice);
        long bobAccount = api.openActiveAccount(bob);
        assertThat(api.deposit(alice.token(), aliceAccount, 5_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> response = api.transfer(bob.token(), aliceAccount, bobAccount, 1_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(balanceOf(aliceAccount)).isEqualTo(5_000);
        assertThat(balanceOf(bobAccount)).isZero();
        assertThat(countLedgerEntries(bobAccount)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE from_account_id = ? AND kind = 'TRANSFER' AND status = 'POSTED'",
                Long.class, aliceAccount)).isZero();
    }

    @Test
    void adminCannotWithdrawFromAHoldersAccount() {
        TestUser admin = api.loginAdmin();
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);
        assertThat(api.deposit(holder.token(), account, 5_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);

        ResponseEntity<JsonNode> response = api.withdraw(admin.token(), account, 1_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(balanceOf(account)).isEqualTo(5_000);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE from_account_id = ? AND initiated_by = ? "
                        + "AND kind = 'WITHDRAWAL' AND status = 'REJECTED' AND reject_reason = 'NOT_AUTHORISED'",
                Long.class, account, admin.id())).isEqualTo(1);
    }

    @Test
    void auditorCannotMoveMoney() {
        TestUser auditor = api.newAuditor();
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);
        long otherAccount = api.openActiveAccount(api.newHolder());
        assertThat(api.deposit(holder.token(), account, 5_000).getStatusCode()).isEqualTo(HttpStatus.CREATED);
        long entriesBefore = countLedgerEntries(account);

        assertThat(api.deposit(auditor.token(), account, 1_000).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.withdraw(auditor.token(), account, 1_000).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(api.transfer(auditor.token(), account, otherAccount, 1_000).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(balanceOf(account)).isEqualTo(5_000);
        assertThat(balanceOf(otherAccount)).isZero();
        assertThat(countLedgerEntries(account)).isEqualTo(entriesBefore);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE initiated_by = ? AND status = 'REJECTED'",
                Long.class, auditor.id())).isEqualTo(3);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE initiated_by = ? AND status = 'POSTED'",
                Long.class, auditor.id())).isZero();
    }

    @Test
    void moneyEndpointWithoutTokenIsUnauthorised() {
        TestUser holder = api.newHolder();
        long account = api.openActiveAccount(holder);

        ResponseEntity<JsonNode> response = api.deposit(null, account, 1_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(balanceOf(account)).isZero();
        assertThat(countTransactionsInvolving(account)).isZero();
    }

    @Test
    void registerIgnoresARoleInTheBody() {
        String email = Api.uniqueEmail("sneaky");

        ResponseEntity<JsonNode> response = api.send(HttpMethod.POST, "/api/auth/register", null,
                Map.of("email", email, "fullName", "Sneaky", "password", Api.USER_PASSWORD, "role", "ADMIN"), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().get("role").asText()).isEqualTo("HOLDER");
        assertThat(jdbc.queryForObject("SELECT role FROM users WHERE email = ?", String.class, email))
                .isEqualTo("HOLDER");
    }

    @Test
    void holderCannotListUsers() {
        TestUser holder = api.newHolder();

        ResponseEntity<JsonNode> response = api.send(HttpMethod.GET, "/api/admin/users", holder.token(), null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM audit_log WHERE actor_id = ? AND action = 'ADMIN_USERS_LISTED'",
                Long.class, holder.id())).isZero();
    }

    @Test
    void moneyMovementOnAPendingAccountIsRefused() {
        TestUser holder = api.newHolder();
        long pendingAccount = api.requestAccount(holder);

        ResponseEntity<JsonNode> response = api.deposit(holder.token(), pendingAccount, 1_000);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("rejectReason").asText()).isEqualTo("ACCOUNT_NOT_ACTIVE");
        assertThat(balanceOf(pendingAccount)).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM transactions WHERE to_account_id = ? AND status = 'REJECTED' "
                        + "AND reject_reason = 'ACCOUNT_NOT_ACTIVE'",
                Long.class, pendingAccount)).isEqualTo(1);
    }
}
