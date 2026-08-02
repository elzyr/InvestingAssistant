package pl.investassist.analytics.dto.response;

import java.math.BigDecimal;
import java.util.List;
import pl.investassist.analytics.dto.ProjectionPointDto;

public record InvestmentProjectionResponse(
    BigDecimal finalNominalValue,
    BigDecimal finalRealValue,
    BigDecimal totalInvested,
    BigDecimal totalProfit,
    List<ProjectionPointDto> timeline) {}
