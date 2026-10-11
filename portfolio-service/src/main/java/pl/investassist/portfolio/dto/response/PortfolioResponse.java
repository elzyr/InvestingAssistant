package pl.investassist.portfolio.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.enums.Currency;

public record PortfolioResponse(
    UUID id,
    UUID userId,
    String name,
    String description,
    Currency baseCurrency,
    LocalDateTime createdAt) {

  public static PortfolioResponse from(Portfolio portfolio) {
    if (portfolio == null) {
      return null;
    }
    return new PortfolioResponse(
        portfolio.getId(),
        portfolio.getUserId(),
        portfolio.getName(),
        portfolio.getDescription(),
        portfolio.getBaseCurrency(),
        portfolio.getCreatedAt());
  }
}
