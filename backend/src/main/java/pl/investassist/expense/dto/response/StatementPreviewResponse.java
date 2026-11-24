package pl.investassist.expense.dto.response;

import java.time.LocalDate;
import java.util.List;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.StatementFormat;
import pl.investassist.expense.parser.ReconciliationResult;

public record StatementPreviewResponse(
    String fileName,
    String fileHashSha256,
    boolean alreadyUploadedFile,
    StatementFormat format,
    BankProfile detectedBankProfile,
    String currency,
    String baseCurrency,
    LocalDate periodStart,
    LocalDate periodEnd,
    ReconciliationResult reconciliation,
    boolean aiUsed,
    String aiWarning,
    int fxDegradedCount,
    List<String> warnings,
    int duplicateCount,
    int needsReviewCount,
    List<PreviewTransactionItem> transactions) {}
