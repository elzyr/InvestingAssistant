package pl.investassist.portfolio.dto.response;

import pl.investassist.portfolio.valuation.TaxMode;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record AnnualReportResponse(
        UUID portfolioId,
        String baseCurrency,
        int year,
        TaxMode taxMode,
        boolean isCurrentYear,
        List<Integer> availableYears,
        BigDecimal ytdPortfolioValue,
        BigDecimal ytdInvested,
        BigDecimal ytdProfitGross,
        BigDecimal ytdReturnGrossPct,
        BigDecimal ytdProfitNet,
        BigDecimal ytdReturnNetPct,
        BigDecimal ytdTwrPct,
        BigDecimal ytdRealReturnPct,
        BigDecimal avgCpi,
        List<MonthlyReportDto> months,
        boolean degraded
) {

    public AnnualReportResponse(
            UUID portfolioId,
            String baseCurrency,
            int year,
            TaxMode taxMode,
            boolean isCurrentYear,
            List<Integer> availableYears,
            BigDecimal ytdPortfolioValue,
            BigDecimal ytdInvested,
            BigDecimal ytdProfitGross,
            BigDecimal ytdReturnGrossPct,
            BigDecimal ytdProfitNet,
            BigDecimal ytdReturnNetPct,
            BigDecimal ytdTwrPct,
            BigDecimal ytdRealReturnPct,
            BigDecimal avgCpi,
            List<MonthlyReportDto> months) {
        this(
                portfolioId, baseCurrency, year, taxMode, isCurrentYear, availableYears,
                ytdPortfolioValue, ytdInvested, ytdProfitGross, ytdReturnGrossPct,
                ytdProfitNet, ytdReturnNetPct, ytdTwrPct, ytdRealReturnPct, avgCpi, months, false);
    }

    public boolean isDegraded() {
        return degraded;
    }

    public enum MonthStatus {
        RECORDED,
        BEFORE_PORTFOLIO,
        FUTURE
    }

    public record MonthlyReportDto(
            int monthIndex,
            MonthStatus status,
            BigDecimal portfolioValue,
            BigDecimal investedTotal,
            BigDecimal profitGross,
            BigDecimal returnGrossPct,
            BigDecimal profitNet,
            BigDecimal returnNetPct,
            BigDecimal profitNetMom,
            BigDecimal returnNetMomPct,
            BigDecimal cpiInflation,
            boolean inflationIsFallback,
            BigDecimal realProfitPct,
            List<MonthlyCategoryDto> breakdown,
            List<MonthlyPositionDto> positions
    ) {}

    public record MonthlyCategoryDto(
            String categoryCode,
            BigDecimal invested,
            BigDecimal currentValue,
            BigDecimal profitGross,
            BigDecimal profitNet,
            BigDecimal returnGrossPct,
            BigDecimal returnNetPct,
            BigDecimal sharePct
    ) {}

    public record MonthlyPositionDto(
            String key,
            String symbol,
            String name,
            String categoryCode,
            BigDecimal invested,
            BigDecimal currentValue,
            BigDecimal profitGross,
            BigDecimal profitNet,
            BigDecimal profitNetMom,
            BigDecimal returnGrossPct,
            BigDecimal returnNetPct,
            BigDecimal sharePct
    ) {}
}
