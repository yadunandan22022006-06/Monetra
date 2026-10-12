package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, Long> {

    Optional<UserAccount> findByEmail(String email);

    boolean existsByEmail(String email);

    Optional<UserAccount> findFirstByPhoneKey(String phoneKey);

    List<UserAccount> findByPhoneKeyIn(Collection<String> phoneKeys);
}
