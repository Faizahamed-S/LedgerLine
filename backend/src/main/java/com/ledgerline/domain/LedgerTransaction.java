package com.ledgerline.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

@Entity
@Immutable
@Table(name = "transactions")
public class LedgerTransaction {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Enumerated(EnumType.STRING)
    @Column(name = "kind", nullable = false)
    private TransactionKind kind;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TransactionStatus status;

    @Column(name = "amount", nullable = false)
    private long amount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "from_account_id")
    private Account fromAccount;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "to_account_id")
    private Account toAccount;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "initiated_by", nullable = false)
    private User initiatedBy;

    @Column(name = "reject_reason")
    private String rejectReason;

    @Column(name = "balance_at_decision")
    private Long balanceAtDecision;

    @Generated
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    protected LedgerTransaction() {
    }

    private LedgerTransaction(TransactionKind kind, TransactionStatus status, long amount,
                              Account fromAccount, Account toAccount, User initiatedBy,
                              String rejectReason, Long balanceAtDecision) {
        this.kind = kind;
        this.status = status;
        this.amount = amount;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.initiatedBy = initiatedBy;
        this.rejectReason = rejectReason;
        this.balanceAtDecision = balanceAtDecision;
    }

    public static LedgerTransaction posted(TransactionKind kind, long amount,
                                           Account fromAccount, Account toAccount, User initiatedBy) {
        return new LedgerTransaction(kind, TransactionStatus.POSTED, amount,
                fromAccount, toAccount, initiatedBy, null, null);
    }

    public static LedgerTransaction rejected(TransactionKind kind, long amount,
                                             Account fromAccount, Account toAccount, User initiatedBy,
                                             String rejectReason, Long balanceAtDecision) {
        return new LedgerTransaction(kind, TransactionStatus.REJECTED, amount,
                fromAccount, toAccount, initiatedBy, rejectReason, balanceAtDecision);
    }

    public Long getId() {
        return id;
    }

    public TransactionKind getKind() {
        return kind;
    }

    public TransactionStatus getStatus() {
        return status;
    }

    public long getAmount() {
        return amount;
    }

    public Account getFromAccount() {
        return fromAccount;
    }

    public Account getToAccount() {
        return toAccount;
    }

    public User getInitiatedBy() {
        return initiatedBy;
    }

    public String getRejectReason() {
        return rejectReason;
    }

    public Long getBalanceAtDecision() {
        return balanceAtDecision;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
