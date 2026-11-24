package pl.investassist.expense.parser;

import java.time.LocalDate;
import java.util.List;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.StatementFormat;

public record IngestedStatement(
    String fileName,
    String fileHashSha256,
    StatementFormat format,
    BankProfile detectedBankProfile,
    String currency,
    LocalDate periodStart,
    LocalDate periodEnd,
    ReconciliationResult reconciliation,
    List<ParsedTransactionRow> rows,
    List<String> warnings) {}
