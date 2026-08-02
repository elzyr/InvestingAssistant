package pl.investassist.analytics.dto.response;

import java.math.BigDecimal;
import java.util.List;
import pl.investassist.analytics.dto.MonteCarloPoint;

public record MonteCarloResponse(
    BigDecimal worstCaseFinal,
    BigDecimal medianFinal,
    BigDecimal bestCaseFinal,
    List<MonteCarloPoint> timeline) {}
