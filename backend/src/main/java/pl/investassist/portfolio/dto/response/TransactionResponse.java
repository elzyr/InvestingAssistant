package pl.investassist.portfolio.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.common.enums.Currency;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;

public record TransactionResponse(
    UUID id,
    UUID instrumentId,
    TransactionType transactionType,
    BigDecimal quantity,
    BigDecimal price,
    BigDecimal commission,
    Currency currency,
    BigDecimal exchangeRate,
    LocalDateTime executedAt,
    String seriesName,
    BigDecimal bondRate,
    BigDecimal bondMargin,
    Integer termMonths,
    BigDecimal baseAmount,
    BigDecimal exchangeRateToBase) {

  public TransactionResponse(
      UUID id,
      UUID instrumentId,
      TransactionType transactionType,
      BigDecimal quantity,
      BigDecimal price,
      BigDecimal commission,
      Currency currency,
      BigDecimal exchangeRate,
      LocalDateTime executedAt,
      String seriesName,
      BigDecimal bondRate,
      BigDecimal bondMargin,
      Integer termMonths,
      BigDecimal baseAmount) {
    this(
        id,
        instrumentId,
        transactionType,
        quantity,
        price,
        commission,
        currency,
        exchangeRate,
        executedAt,
        seriesName,
        bondRate,
        bondMargin,
        termMonths,
        baseAmount,
        null);
  }

  public static TransactionResponse from(Transaction transaction) {
    if (transaction == null) {
      return null;
    }
    return new TransactionResponse(
        transaction.getId(),
        transaction.getInstrumentId(),
        transaction.getTransactionType(),
        transaction.getQuantity(),
        transaction.getPrice(),
        transaction.getCommission(),
        transaction.getCurrency(),
        transaction.getExchangeRate(),
        transaction.getExecutedAt(),
        transaction.getSeriesName(),
        transaction.getBondRate(),
        transaction.getBondMargin(),
        transaction.getTermMonths(),
        transaction.getBaseAmount(),
        transaction.getExchangeRateToBase());
  }
}
