package pl.investassist.portfolio.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import pl.investassist.portfolio.enums.Currency;

public record CreatePortfolioRequest(
    @NotBlank String name, String description, @NotNull Currency baseCurrency) {}
