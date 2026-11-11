package com.ledgerline.repository;

import com.ledgerline.domain.LedgerEntry;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface LedgerEntryRepository extends JpaRepository<LedgerEntry, Long> {

    /** The account's newest entry, i.e. the current end of its hash chain. */
    Optional<LedgerEntry> findTopByAccountIdOrderByIdDesc(Long accountId);

    @Query("""
            select e from LedgerEntry e
            join fetch e.transaction t
            join fetch t.initiatedBy
            where e.account.id = :accountId
            order by t.createdAt desc, t.id desc
            """)
    List<LedgerEntry> findStatementEntries(@Param("accountId") Long accountId, Pageable pageable);
}
