package com.monetra;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;

public interface ReminderSentRepository extends JpaRepository<ReminderSent, Long> {

    boolean existsByExpenseIdAndDueDateAndKind(Long expenseId, LocalDate dueDate, String kind);

    void deleteByExpenseId(Long expenseId);
}
