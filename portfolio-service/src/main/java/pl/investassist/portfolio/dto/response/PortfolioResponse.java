package pl.investassist.portfolio.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.portfolio.enums.Currency;

public record PortfolioResponse(
    UUID id,
    UUID userId,
    String name,
    String description,
    Currency baseCurrency,
    LocalDateTime createdAt) {}
