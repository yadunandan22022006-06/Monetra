package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankAccountRepository extends JpaRepository<BankAccount, Long> {

    List<BankAccount> findByUserIdOrderByIdAsc(Long userId);

    List<BankAccount> findByUserIdAndConsentId(Long userId, String consentId);

    Optional<BankAccount> findByIdAndUserId(Long id, Long userId);
}
