package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface HomeExpenseRepository extends JpaRepository<HomeExpense, Long> {

    List<HomeExpense> findByUserIdAndActiveTrueOrderByCreatedAtAsc(Long userId);

    Optional<HomeExpense> findByIdAndUserId(Long id, Long userId);
}
