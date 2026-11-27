package pl.investassist.portfolio.dto.response;

import pl.investassist.portfolio.valuation.TaxMode;
import pl.investassist.portfolio.valuation.Timeframe;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PortfolioTimelineResponse(
        UUID portfolioId,
        String baseCurrency,
        Timeframe timeframe,
        TaxMode taxMode,
        BigDecimal periodProfit,
        BigDecimal periodProfitPct,
        BigDecimal periodTwrPct,
        List<TimelinePointDto> points,
        List<ProfitContributorDto> contributors,
        boolean degraded
) {

    public PortfolioTimelineResponse(
            UUID portfolioId,
            String baseCurrency,
            Timeframe timeframe,
            TaxMode taxMode,
            BigDecimal periodProfit,
            BigDecimal periodProfitPct,
            BigDecimal periodTwrPct,
            List<TimelinePointDto> points,
            List<ProfitContributorDto> contributors) {
        this(portfolioId, baseCurrency, timeframe, taxMode, periodProfit, periodProfitPct, periodTwrPct, points, contributors, false);
    }

    public boolean isDegraded() {
        return degraded;
    }

    public record TimelinePointDto(
            LocalDate date,
            BigDecimal value,
            BigDecimal invested,
            BigDecimal netProfit,
            BigDecimal returnPct,
            BigDecimal stepGain,
            BigDecimal equityValue,
            BigDecimal bondValue,
            List<ProfitContributorDto> contributors
    ) {}

    public record ProfitContributorDto(
            String id,
            String name,
            String symbol,
            String category,
            BigDecimal valuationBase,
            BigDecimal costBase,
            BigDecimal gainBase,
            BigDecimal returnContributionPct,
            BigDecimal sharePct,
            boolean isOther
    ) {}
}
