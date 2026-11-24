package pl.investassist.expense.classifier;

import java.math.BigDecimal;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

public record ClassifiedTransaction(
    String id,
    String merchantKey,
    String merchantDisplay,
    String phraseKey,
    boolean intermediary,
    FlowKind flowKind,
    ExpenseCategory category,
    ClassificationSource source,
    BigDecimal confidence,
    boolean needsReview) {}
