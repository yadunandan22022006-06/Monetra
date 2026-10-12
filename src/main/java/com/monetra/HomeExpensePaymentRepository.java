package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HomeExpensePaymentRepository extends JpaRepository<HomeExpensePayment, Long> {

    List<HomeExpensePayment> findByExpenseIdIn(Collection<Long> expenseIds);

    Optional<HomeExpensePayment> findByExpenseIdAndDueDate(Long expenseId, LocalDate dueDate);

    void deleteByExpenseId(Long expenseId);
}
