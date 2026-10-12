package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ContactRepository extends JpaRepository<Contact, Long> {

    List<Contact> findByUserIdOrderByNameAsc(Long userId);

    Optional<Contact> findByUserIdAndPhoneKey(Long userId, String phoneKey);
}
