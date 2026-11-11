package com.ledgerline.config;

import com.ledgerline.support.IntegrationTestBase;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import static org.assertj.core.api.Assertions.assertThat;

class SwaggerTest extends IntegrationTestBase {

    @Test
    void apiDocsArePublicAndDeclareTheBearerScheme() {
        long auditRowsBefore = jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class);

        ResponseEntity<String> response = rest.getForEntity("/v3/api-docs", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("bearerAuth");
        assertThat(response.getBody()).contains("Idempotency-Key");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM audit_log", Long.class))
                .as("reading the API docs touches no data")
                .isEqualTo(auditRowsBefore);
    }
}
