package pl.investassist.expense.analytics;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

public record AnalyticsTransaction(
    UUID id,
    LocalDate txDate,
    String merchantKey,
    String merchantDisplay,
    BigDecimal baseAmount,
    FlowKind flowKind,
    ExpenseCategory category,
    boolean excluded,
    boolean fxDegraded,
    boolean needsReview) {}
