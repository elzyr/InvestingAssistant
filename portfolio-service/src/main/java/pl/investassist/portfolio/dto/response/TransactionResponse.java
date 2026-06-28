package pl.investassist.portfolio.dto.response;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.portfolio.enums.Currency;
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
    LocalDateTime executedAt) {}
