package pl.investassist.expense.classifier;

import java.math.BigDecimal;
import java.time.LocalDate;

public record ClassifiableRow(
    String id,
    LocalDate txDate,
    String rawDescription,
    BigDecimal amount,
    String currency) {}
