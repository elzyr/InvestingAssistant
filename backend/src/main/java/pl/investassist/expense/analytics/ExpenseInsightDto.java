package pl.investassist.expense.analytics;

import java.util.Map;

public record ExpenseInsightDto(
    String code,
    String severity,
    Map<String, Object> params) {}
