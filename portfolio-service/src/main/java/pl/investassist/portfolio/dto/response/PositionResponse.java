package pl.investassist.portfolio.dto.response;

import java.math.BigDecimal;
import java.util.UUID;
import pl.investassist.portfolio.enums.Currency;

public record PositionResponse(
    UUID id,
    UUID instrumentId,
    BigDecimal quantity,
    BigDecimal averageBuyPrice,
    Currency currency) {}
