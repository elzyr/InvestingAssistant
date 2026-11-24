package pl.investassist.expense.dto.request;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import pl.investassist.expense.enums.ClassificationSource;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

public record CommitTransactionItem(
    @NotNull LocalDate txDate,
    LocalDate bookingDate,
    @NotBlank String rawDescription,
    @NotBlank @Size(max = 160) String merchantKey,
    @NotBlank @Size(max = 160) String merchantDisplay,
    @Size(max = 160) String phraseKey,
    @NotNull BigDecimal amount,
    @NotBlank @Size(max = 8) String currency,
    @NotNull BigDecimal baseAmount,
    @NotBlank @Size(max = 8) String baseCurrency,
    @NotNull BigDecimal fxRate,
    Boolean fxDegraded,
    @NotNull FlowKind flowKind,
    @NotNull ExpenseCategory category,
    @NotNull ClassificationSource classificationSource,
    @NotNull @DecimalMin("0.00") @DecimalMax("1.00") BigDecimal confidence,
    @NotBlank @Size(max = 64) String fingerprint,
    Boolean excluded,
    Boolean rememberRule,
    @Size(max = 255) String userNote) {

  public CommitTransactionItem {
    fxDegraded = Boolean.TRUE.equals(fxDegraded);
    excluded = Boolean.TRUE.equals(excluded);
    rememberRule = Boolean.TRUE.equals(rememberRule);
  }
}
