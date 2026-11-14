package pl.investassist.portfolio.dto.response;

import pl.investassist.portfolio.valuation.TaxMode;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PortfolioValuationResponse(
        UUID portfolioId,
        String baseCurrency,
        TaxMode taxMode,
        ValuationSummaryDto summary,
        List<ValuationPositionDto> positions,
        ProjectionSeedDto projectionSeed
) {

    public boolean isDegraded() {
        return positions != null && positions.stream().anyMatch(ValuationPositionDto::isPriceUnavailable);
    }

    public record ValuationSummaryDto(
            BigDecimal totalValue,
            BigDecimal grossTotalValue,
            BigDecimal totalInvested,
            BigDecimal netProfit,
            BigDecimal netProfitPct,
            BigDecimal grossProfit,
            BigDecimal realizedProfit,
            BigDecimal taxPaid,
            BigDecimal dividendsTotal,
            BigDecimal dailyChange,
            BigDecimal dailyChangePct,
            BigDecimal xirrPct,
            BigDecimal realProfitAmount,
            BigDecimal realReturnPct,
            BigDecimal realXirrPct,
            BigDecimal currentCpiPct,
            int positionsCount
    ) {}

    public record ValuationPositionDto(
            String id,
            UUID instrumentId,
            String symbol,
            String name,
            String category,
            String categoryCode,
            String quoteCurrency,
            BigDecimal quantity,
            BigDecimal currentPriceNative,
            BigDecimal averageBuyPriceNative,
            BigDecimal valueBase,
            BigDecimal investedBase,
            BigDecimal profitBase,
            BigDecimal grossProfitBase,
            BigDecimal profitPct,
            BigDecimal dailyChangeBase,
            BigDecimal dailyChangePct,
            BigDecimal allocationPct,
            BigDecimal xirrPct,
            BigDecimal dividendsBase,
            BigDecimal realizedProfitBase,
            String bondFamily,
            BigDecimal bondRate,
            BigDecimal bondRateMin,
            BigDecimal bondRateMax,
            BigDecimal bondMargin,
            boolean isPriceUnavailable,
            boolean isCpiUnavailable,
            String subPositionsType,
            FxDecompositionDto fxDecomposition,
            List<ValuationSubPositionDto> subPositions
    ) {

        public ValuationPositionDto withAllocation(BigDecimal grossTotalFinal) {
            BigDecimal allocation = (grossTotalFinal != null && grossTotalFinal.compareTo(BigDecimal.ZERO) > 0 && valueBase != null)
                    ? valueBase.multiply(BigDecimal.valueOf(100)).divide(grossTotalFinal, 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            List<ValuationSubPositionDto> subs = subPositions == null ? List.of() : subPositions.stream()
                    .map(sp -> sp.withSharePercent(grossTotalFinal))
                    .toList();
            return new ValuationPositionDto(
                    id, instrumentId, symbol, name, category, categoryCode, quoteCurrency,
                    quantity, currentPriceNative, averageBuyPriceNative, valueBase, investedBase,
                    profitBase, grossProfitBase, profitPct, dailyChangeBase, dailyChangePct,
                    allocation, xirrPct, dividendsBase, realizedProfitBase,
                    bondFamily, bondRate, bondRateMin, bondRateMax, bondMargin,
                    isPriceUnavailable, isCpiUnavailable, subPositionsType, fxDecomposition, subs);
        }
    }

    public record ValuationSubPositionDto(
            String id,
            UUID transactionId,
            String label,
            LocalDate date,
            BigDecimal quantity,
            BigDecimal txPrice,
            String txCurrency,
            BigDecimal sharePercent,
            BigDecimal investedBase,
            BigDecimal valueBase,
            BigDecimal profitBase,
            BigDecimal profitPct,
            BigDecimal xirrPct,
            BigDecimal currentPriceNative,
            BigDecimal averageBuyPriceNative,
            BigDecimal bondRate,
            BigDecimal bondMargin,
            FxDecompositionDto fxDecomposition
    ) {

        public ValuationSubPositionDto withSharePercent(BigDecimal grossTotalFinal) {
            BigDecimal share = (grossTotalFinal != null && grossTotalFinal.compareTo(BigDecimal.ZERO) > 0 && valueBase != null)
                    ? valueBase.multiply(BigDecimal.valueOf(100)).divide(grossTotalFinal, 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new ValuationSubPositionDto(
                    id, transactionId, label, date, quantity, txPrice, txCurrency,
                    share, investedBase, valueBase, profitBase, profitPct, xirrPct,
                    currentPriceNative, averageBuyPriceNative, bondRate, bondMargin, fxDecomposition);
        }
    }

    public record FxDecompositionDto(
            String quoteCurrency,
            String baseCurrency,
            BigDecimal marketReturnPct,
            BigDecimal fxReturnPct
    ) {}

    public record ProjectionSeedDto(
            BigDecimal startTotalValue,
            BigDecimal startEquityValue,
            BigDecimal startBondValue,
            BigDecimal startInvestedCapital,
            BigDecimal weightedBondYieldPct,
            BigDecimal currentCpiPct,
            BigDecimal belkaTaxRate,
            List<EquityWeightDto> equityWeights
    ) {}

    public record EquityWeightDto(
            String symbol,
            String name,
            String categoryCode,
            String quoteCurrency,
            BigDecimal valueBase,
            BigDecimal weightPct
    ) {

        public EquityWeightDto withWeightPct(BigDecimal totalEquityValue) {
            BigDecimal weight = (totalEquityValue != null && totalEquityValue.compareTo(BigDecimal.ZERO) > 0 && valueBase != null)
                    ? valueBase.multiply(BigDecimal.valueOf(100)).divide(totalEquityValue, 2, java.math.RoundingMode.HALF_UP)
                    : BigDecimal.ZERO;
            return new EquityWeightDto(symbol, name, categoryCode, quoteCurrency, valueBase, weight);
        }
    }
}
