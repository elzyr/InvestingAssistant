package pl.investassist.analytics.dto.event;

import java.math.BigDecimal;
import java.time.Instant;

public record ProjectionCompletedEvent(
        String simulationType,
        BigDecimal nominalValueFinal,
        BigDecimal realValueFinal,
        BigDecimal totalProfitFinal,
        Instant timestamp
) {}
