package pl.investassist.portfolio.dto.response;

import java.time.LocalDateTime;
import java.util.UUID;
import pl.investassist.common.enums.AccountType;
import pl.investassist.common.enums.Currency;
import pl.investassist.portfolio.entity.Portfolio;

public record PortfolioResponse(
    UUID id,
    UUID userId,
    String name,
    String description,
    Currency baseCurrency,
    AccountType accountType,
    LocalDateTime createdAt) {

  public PortfolioResponse(
      UUID id,
      UUID userId,
      String name,
      String description,
      Currency baseCurrency,
      LocalDateTime createdAt) {
    this(id, userId, name, description, baseCurrency, AccountType.STANDARD, createdAt);
  }

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
        portfolio.getAccountType() != null ? portfolio.getAccountType() : AccountType.STANDARD,
        portfolio.getCreatedAt());
  }
}
