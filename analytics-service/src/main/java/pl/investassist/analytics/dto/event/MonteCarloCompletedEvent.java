package pl.investassist.analytics.dto.event;

import java.math.BigDecimal;
import java.time.Instant;

public record MonteCarloCompletedEvent(
        String simulationType,
        BigDecimal worstCaseFinal,
        BigDecimal medianFinal,
        BigDecimal bestCaseFinal,
        Instant timestamp
) {}
