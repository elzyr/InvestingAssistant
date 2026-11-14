package pl.investassist.portfolio.dto.request;

import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PastOrPresent;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.common.enums.Currency;
import pl.investassist.portfolio.enums.TransactionType;

public record CreateTransactionRequest(
    @NotNull UUID instrumentId,
    @NotNull TransactionType transactionType,
    @NotNull @Positive @Digits(integer = 30, fraction = 6) BigDecimal price,
    @NotNull @Positive @Digits(integer = 30, fraction = 8) BigDecimal quantity,
    @NotNull @PositiveOrZero @Digits(integer = 30, fraction = 6) BigDecimal commission,
    @NotNull Currency currency,
    @PastOrPresent(message = "Transaction date cannot be in the future")
    LocalDateTime executedAt,
    @Size(max = 50) String seriesName,
    @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal bondRate,
    @PositiveOrZero @Digits(integer = 6, fraction = 4) BigDecimal bondMargin,
    @Positive Integer termMonths,
    @Positive @Digits(integer = 34, fraction = 4) BigDecimal baseAmount,
    @Positive @Digits(integer = 4, fraction = 8) BigDecimal exchangeRateToBase,
    @Positive @Digits(integer = 4, fraction = 8) BigDecimal instrumentToTxRate,
    @jakarta.validation.constraints.DecimalMin("0.00") @jakarta.validation.constraints.DecimalMax("100.00") @Digits(integer = 3, fraction = 2) BigDecimal withholdingTaxPct) {

  public CreateTransactionRequest(
      UUID instrumentId,
      TransactionType transactionType,
      BigDecimal price,
      BigDecimal quantity,
      BigDecimal commission,
      Currency currency,
      LocalDateTime executedAt,
      String seriesName,
      BigDecimal bondRate,
      BigDecimal bondMargin,
      Integer termMonths,
      BigDecimal baseAmount) {
    this(
        instrumentId,
        transactionType,
        price,
        quantity,
        commission,
        currency,
        executedAt,
        seriesName,
        bondRate,
        bondMargin,
        termMonths,
        baseAmount,
        null,
        null,
        null);
  }

  public CreateTransactionRequest(
      UUID instrumentId,
      TransactionType transactionType,
      BigDecimal price,
      BigDecimal quantity,
      BigDecimal commission,
      Currency currency,
      LocalDateTime executedAt,
      String seriesName,
      BigDecimal bondRate,
      BigDecimal bondMargin,
      Integer termMonths,
      BigDecimal baseAmount,
      BigDecimal exchangeRateToBase) {
    this(
        instrumentId,
        transactionType,
        price,
        quantity,
        commission,
        currency,
        executedAt,
        seriesName,
        bondRate,
        bondMargin,
        termMonths,
        baseAmount,
        exchangeRateToBase,
        null,
        null);
  }

  public CreateTransactionRequest(
      UUID instrumentId,
      TransactionType transactionType,
      BigDecimal price,
      BigDecimal quantity,
      BigDecimal commission,
      Currency currency,
      LocalDateTime executedAt,
      String seriesName,
      BigDecimal bondRate,
      BigDecimal bondMargin,
      Integer termMonths,
      BigDecimal baseAmount,
      BigDecimal exchangeRateToBase,
      BigDecimal instrumentToTxRate) {
    this(
        instrumentId,
        transactionType,
        price,
        quantity,
        commission,
        currency,
        executedAt,
        seriesName,
        bondRate,
        bondMargin,
        termMonths,
        baseAmount,
        exchangeRateToBase,
        instrumentToTxRate,
        null);
  }
}
