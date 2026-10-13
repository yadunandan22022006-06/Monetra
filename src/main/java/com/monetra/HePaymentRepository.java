package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HePaymentRepository extends JpaRepository<HePayment, Long> {

    List<HePayment> findByUserIdOrderByCreatedAtDesc(Long userId);

    Optional<HePayment> findByUserIdAndRequestId(Long userId, String requestId);
}
