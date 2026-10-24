package pl.investassist.portfolio.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.ArrayList;
import java.util.UUID;
import pl.investassist.common.enums.Currency;
import pl.investassist.portfolio.entity.Portfolio;

public record CreatePortfolioRequest(
    @NotBlank String name, String description, @NotNull Currency baseCurrency) {

  public Portfolio toEntity(UUID userId) {
    Portfolio portfolio = new Portfolio();
    portfolio.setUserId(userId);
    portfolio.setName(name);
    portfolio.setDescription(description);
    portfolio.setBaseCurrency(baseCurrency);
    portfolio.setPositions(new ArrayList<>());
    portfolio.setTransactions(new ArrayList<>());
    return portfolio;
  }
}
