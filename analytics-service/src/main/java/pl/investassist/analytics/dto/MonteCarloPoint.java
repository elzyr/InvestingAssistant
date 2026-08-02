package pl.investassist.analytics.dto;

import java.math.BigDecimal;

public record MonteCarloPoint(
    int year, BigDecimal worstCase, BigDecimal medianCase, BigDecimal bestCase) {}
