package pl.investassist.expense.dto.response;

import java.util.List;

public record ExpenseStatusResponse(
    boolean aiEnabled,
    String aiProvider,
    String aiModel,
    String baseCurrency,
    long maxFileSizeBytes,
    List<String> supportedFormats,
    List<String> supportedBankProfiles) {}
