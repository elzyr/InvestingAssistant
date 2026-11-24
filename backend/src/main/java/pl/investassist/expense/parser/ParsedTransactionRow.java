package pl.investassist.expense.parser;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ParsedTransactionRow(
    LocalDate txDate,
    LocalDate bookingDate,
    String rawDescription,
    BigDecimal amount,
    String currency) {}
