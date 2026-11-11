package com.ledgerline.repository;

import com.ledgerline.domain.Account;
import com.ledgerline.domain.AccountStatus;
import com.ledgerline.domain.AccountType;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface AccountRepository extends JpaRepository<Account, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select a from Account a where a.id = :id")
    Optional<Account> findByIdForUpdate(@Param("id") Long id);

    @EntityGraph(attributePaths = "owner")
    List<Account> findByOwnerIdOrderByIdAsc(Long ownerId);

    @EntityGraph(attributePaths = "owner")
    List<Account> findByTypeOrderByIdAsc(AccountType type);

    @EntityGraph(attributePaths = "owner")
    List<Account> findByStatusAndTypeOrderByIdAsc(AccountStatus status, AccountType type);

    @EntityGraph(attributePaths = "owner")
    List<Account> findAllByOrderByIdAsc();

    long countByOwnerId(Long ownerId);

    /** Id only: must not put an Account into the persistence context before it is locked. */
    @Query("select a.id from Account a where a.type = :type")
    Optional<Long> findIdByType(@Param("type") AccountType type);
}
