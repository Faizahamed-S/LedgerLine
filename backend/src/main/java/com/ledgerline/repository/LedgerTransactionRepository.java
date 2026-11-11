package com.ledgerline.repository;

import com.ledgerline.domain.LedgerTransaction;
import com.ledgerline.domain.TransactionStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;

public interface LedgerTransactionRepository extends JpaRepository<LedgerTransaction, Long> {

    @Query("""
            select t from LedgerTransaction t
            join fetch t.initiatedBy
            left join t.fromAccount fa
            left join t.toAccount ta
            where t.status = :status
              and (fa.id = :accountId or ta.id = :accountId)
            order by t.createdAt desc, t.id desc
            """)
    List<LedgerTransaction> findForAccountByStatus(@Param("accountId") Long accountId,
                                                   @Param("status") TransactionStatus status,
                                                   Pageable pageable);
}
