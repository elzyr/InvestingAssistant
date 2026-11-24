package pl.investassist.expense.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import pl.investassist.expense.enums.ExpenseCategory;

public record OutlierExpenseItem(
    UUID transactionId,
    LocalDate txDate,
    String merchantKey,
    String merchantDisplay,
    ExpenseCategory category,
    BigDecimal amount) {}
