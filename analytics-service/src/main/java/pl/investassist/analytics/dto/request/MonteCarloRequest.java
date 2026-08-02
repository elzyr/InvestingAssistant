package pl.investassist.analytics.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.PositiveOrZero;

import java.math.BigDecimal;

public record MonteCarloRequest(
        @NotNull(message = "You must provide initialInvestment")
        @PositiveOrZero(message = "Initial investment cannot be negative")
        BigDecimal initialInvestment,

        @NotNull(message = "You must provide monthlyContribution")
        @PositiveOrZero(message = "Monthly contribution cannot be negative")
        BigDecimal monthlyContribution,

        @NotNull(message = "You must provide expectedAnnualReturn")
        @Min(value = -1, message = "Expected annual return cannot be lower than -100%")
        Double expectedAnnualReturn,

        @NotNull(message = "You must provide annualVolatility")
        @Positive(message = "Annual volatility must be greater than zero")
        Double annualVolatility,

        @NotNull(message = "You must provide timeHorizonYears")
        @Min(value = 1, message = "Time horizon must be at least 1 year")
        @Max(value = 100, message = "Time horizon can be maximum 100 years")
        Integer timeHorizonYears,

        @NotNull(message = "You must provide simulationsCount")
        @Min(value = 10, message = "Simulations count must be at least 10")
        @Max(value = 50000, message = "Simulations count cannot exceed 50,000")
        Integer simulationsCount
) {}
