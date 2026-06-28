package pl.investassist.portfolio.mapper;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import pl.investassist.portfolio.dto.request.CreatePortfolioRequest;
import pl.investassist.portfolio.dto.response.PortfolioResponse;
import pl.investassist.portfolio.entity.Portfolio;

public final class PortfolioMapper {

  private PortfolioMapper() {}

  public static PortfolioResponse toResponse(Portfolio portfolio) {
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

  public static Portfolio toEntity(CreatePortfolioRequest request, UUID userId) {
    if (request == null) {
      return null;
    }
    Portfolio portfolio = new Portfolio();
    portfolio.setUserId(userId);
    portfolio.setName(request.name());
    portfolio.setDescription(request.description());
    portfolio.setBaseCurrency(request.baseCurrency());
    portfolio.setPositions(new ArrayList<>());
    portfolio.setTransactions(new ArrayList<>());
    return portfolio;
  }
}
