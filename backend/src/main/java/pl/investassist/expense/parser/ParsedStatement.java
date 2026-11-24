package pl.investassist.expense.parser;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import pl.investassist.expense.enums.BankProfile;

public record ParsedStatement(
    BankProfile detectedBankProfile,
    String currency,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal openingBalance,
    BigDecimal closingBalance,
    List<ParsedTransactionRow> rows,
    List<String> warnings) {}
