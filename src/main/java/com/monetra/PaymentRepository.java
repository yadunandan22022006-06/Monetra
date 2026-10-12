package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepository extends JpaRepository<PaymentRecord, String> {

    Optional<PaymentRecord> findByOrderId(String orderId);

    List<PaymentRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    /** Money other people sent to this user (only confirmed payments are shown to the receiver). */
    List<PaymentRecord> findByRecipientUserIdAndStatusOrderByCreatedAtDesc(Long recipientUserId, String status);
}
