package pl.investassist.analytics.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record InvestmentProjectionRequest(
        @NotNull(message = "You must provide initialAmount")
        @PositiveOrZero(message = "Initial amount cannot be negative")
        BigDecimal initialAmount,

        @NotNull(message = "You must provide monthlyContribution")
        @PositiveOrZero(message = "Monthly contribution cannot be negative")
        BigDecimal monthlyContribution,

        @NotNull(message = "You must provide expectedAnnualReturn")
        @Min(value = -1, message = "Expected annual return cannot be lower than -100%")
        Double expectedAnnualReturn,

        @NotNull(message = "You must provide expectedAnnualInflation")
        @Min(value = -1, message = "Expected annual inflation cannot be lower than -100%")
        Double expectedAnnualInflation,

        @NotNull(message = "You must provide years")
        @Min(value = 1, message = "You must project at least 1 year ahead")
        @Max(value = 100, message = "Projection can be for maximum of 100 years")
        Integer years
) {}
