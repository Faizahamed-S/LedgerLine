package com.ledgerline.anomaly;

import com.ledgerline.audit.AuditAction;
import com.ledgerline.audit.AuditService;
import com.ledgerline.common.ConflictException;
import com.ledgerline.common.NotFoundException;
import com.ledgerline.security.CurrentUser;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

/** Lists flags and records human reviews. A review only records a decision; it never acts on accounts or money. */
@Service
public class FlagService {

    static final int MAX_FLAGS = 500;

    private static final String SELECT_FLAGS = """
            SELECT f.id, f.transaction_id, f.account_id, f.rule, f.severity, f.message, f.created_at,
                   r.decision, r.note, r.reviewer_id, u.email AS reviewer_email, r.created_at AS reviewed_at
            FROM anomaly_flags f
            LEFT JOIN flag_reviews r ON r.flag_id = f.id
            LEFT JOIN users u ON u.id = r.reviewer_id
            """;

    private static final String INSERT_REVIEW_SQL = """
            INSERT INTO flag_reviews (flag_id, reviewer_id, decision, note)
            VALUES (?, ?, ?, ?)
            ON CONFLICT (flag_id) DO NOTHING
            RETURNING id
            """;

    private static final RowMapper<FlagResponse> FLAG_MAPPER = FlagService::mapFlag;

    private final JdbcTemplate jdbc;
    private final AuditService auditService;

    public FlagService(JdbcTemplate jdbc, AuditService auditService) {
        this.jdbc = jdbc;
        this.auditService = auditService;
    }

    /** Newest first, at most 500. {@code status} null means all flags. */
    @Transactional(readOnly = true)
    public List<FlagResponse> list(FlagStatus status) {
        String where = status == null ? ""
                : status == FlagStatus.OPEN ? "WHERE r.id IS NULL " : "WHERE r.id IS NOT NULL ";
        return jdbc.query(SELECT_FLAGS + where + "ORDER BY f.created_at DESC, f.id DESC LIMIT " + MAX_FLAGS,
                FLAG_MAPPER);
    }

    @Transactional
    public FlagResponse review(CurrentUser admin, long flagId, ReviewFlagRequest request) {
        FlagResponse flag = find(flagId);
        List<Long> inserted = jdbc.query(INSERT_REVIEW_SQL, (rs, rowNum) -> rs.getLong(1),
                flagId, admin.id(), request.decision().name(), request.note());
        if (inserted.isEmpty()) {
            throw new ConflictException("Flag has already been reviewed");
        }
        auditService.record(admin.id(), AuditAction.FLAG_REVIEWED, AuditService.TARGET_FLAG, flagId, Map.of(
                "decision", request.decision().name(),
                "rule", flag.rule(),
                "accountId", flag.accountId()));
        return find(flagId);
    }

    private FlagResponse find(long flagId) {
        List<FlagResponse> rows = jdbc.query(SELECT_FLAGS + "WHERE f.id = ?", FLAG_MAPPER, flagId);
        if (rows.isEmpty()) {
            throw new NotFoundException("Flag not found");
        }
        return rows.get(0);
    }

    private static FlagResponse mapFlag(ResultSet rs, int rowNum) throws SQLException {
        FlagReviewResponse review = rs.getString("decision") == null ? null : new FlagReviewResponse(
                rs.getString("decision"),
                rs.getString("note"),
                rs.getLong("reviewer_id"),
                rs.getString("reviewer_email"),
                instant(rs, "reviewed_at"));
        return new FlagResponse(
                rs.getLong("id"),
                rs.getObject("transaction_id", Long.class),
                rs.getLong("account_id"),
                rs.getString("rule"),
                rs.getString("severity"),
                rs.getString("message"),
                instant(rs, "created_at"),
                review);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }
}
