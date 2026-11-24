package pl.investassist.expense.dto.response;

public record ReclassifyExpensesResponse(
    int processedCount,
    int updatedCount,
    boolean aiUsed,
    String aiWarning) {}
