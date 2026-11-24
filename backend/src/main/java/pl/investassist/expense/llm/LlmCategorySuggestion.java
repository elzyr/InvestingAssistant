package pl.investassist.expense.llm;

import java.math.BigDecimal;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

public record LlmCategorySuggestion(
    ExpenseCategory category,
    FlowKind flowKind,
    String merchantDisplay,
    BigDecimal confidence) {}
