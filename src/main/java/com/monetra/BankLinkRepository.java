package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BankLinkRepository extends JpaRepository<BankLink, Long> {

    List<BankLink> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<BankLink> findByUserIdAndConsentId(Long userId, String consentId);
}
