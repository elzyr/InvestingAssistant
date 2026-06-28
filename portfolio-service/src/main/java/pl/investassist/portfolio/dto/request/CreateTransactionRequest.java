package pl.investassist.portfolio.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;
import java.math.BigDecimal;
import java.util.UUID;
import pl.investassist.portfolio.enums.Currency;
import pl.investassist.portfolio.enums.TransactionType;

public record CreateTransactionRequest(
    @NotNull UUID instrumentId,
    @NotNull TransactionType transactionType,
    @NotNull @Positive BigDecimal price,
    @NotNull @Positive BigDecimal quantity,
    @NotNull @PositiveOrZero BigDecimal commission,
    @NotNull Currency currency,
    @Positive BigDecimal exchangeRate) {}
