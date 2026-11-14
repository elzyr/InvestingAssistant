package pl.investassist.portfolio.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import pl.investassist.common.enums.Currency;
import pl.investassist.portfolio.entity.Portfolio;

public record CreatePortfolioRequest(
    @NotBlank @Size(max = 255) String name,
    @Size(max = 255) String description,
    @NotNull Currency baseCurrency) {

  public Portfolio toEntity(UUID userId) {
    Portfolio portfolio = new Portfolio();
    portfolio.setUserId(userId);
    portfolio.setName(name);
    portfolio.setDescription(description);
    portfolio.setBaseCurrency(baseCurrency);
    return portfolio;
  }
}
