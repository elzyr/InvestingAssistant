package pl.investassist.expense.dto.response;

import java.math.BigDecimal;
import java.time.LocalDate;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

public record PreviewTransactionItem(
    String id,
    LocalDate txDate,
    LocalDate bookingDate,
    String rawDescription,
    String merchantKey,
    String merchantDisplay,
    String phraseKey,
    boolean intermediary,
    BigDecimal amount,
    String currency,
    BigDecimal baseAmount,
    String baseCurrency,
    BigDecimal fxRate,
    boolean fxDegraded,
    FlowKind flowKind,
    ExpenseCategory category,
    ClassificationSource classificationSource,
    BigDecimal confidence,
    boolean needsReview,
    boolean duplicate,
    String fingerprint) {}
