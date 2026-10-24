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
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
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
    @Positive @Digits(integer = 34, fraction = 4) BigDecimal baseAmount) {

  public Transaction toEntity(Portfolio portfolio) {
    Transaction transaction = new Transaction();
    transaction.setPortfolio(portfolio);
    transaction.setInstrumentId(instrumentId);
    transaction.setTransactionType(transactionType);
    transaction.setQuantity(quantity);
    transaction.setPrice(price);
    transaction.setCommission(commission);
    transaction.setCurrency(currency);
    transaction.setExecutedAt(executedAt != null ? executedAt : LocalDateTime.now());
    transaction.setSeriesName(seriesName);
    transaction.setBondRate(bondRate);
    transaction.setBondMargin(bondMargin);
    transaction.setTermMonths(termMonths);
    transaction.setBaseAmount(baseAmount);
    return transaction;
  }
}
