package pl.investassist.analytics.dto;

import java.math.BigDecimal;

public record ProjectionPointDto(
    int year,
    int month,
    BigDecimal totalInvested,
    BigDecimal nominalValue,
    BigDecimal realValue,
    BigDecimal totalProfit) {}
