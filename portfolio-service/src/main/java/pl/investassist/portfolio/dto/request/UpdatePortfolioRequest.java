package pl.investassist.portfolio.dto.request;

import jakarta.validation.constraints.NotBlank;

public record UpdatePortfolioRequest(@NotBlank String name, String description) {}
