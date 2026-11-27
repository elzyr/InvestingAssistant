package pl.investassist.portfolio.valuation;

import pl.investassist.common.enums.InstrumentType;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.EquityWeightDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.FxDecompositionDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationPositionDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationSubPositionDto;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.port.MarketDataPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class EquityPositionBuilder {

    private EquityPositionBuilder() {
    }

    static void buildEquityPosition(
            ValuationEngine.Accumulator acc,
            UUID instrumentId,
            PortfolioLedger.PositionReplayResult posResult,
            Instrument inst,
            Transaction sampleTx,
            Map<PortfolioLedger.DividendKey, List<MetricsCalculator.CashFlow>> dividendFlowsByKey,
            Map<PortfolioLedger.DividendKey, BigDecimal> remainingDividends,
            Map<PortfolioLedger.DividendKey, BigDecimal> remainingDividendTax,
            ValuationContext ctx) {
        String symbol = inst != null ? inst.getSymbol() : instrumentId.toString();
        String quoteKey = ValuationSupport.normalizeSymbolKey(inst, instrumentId);
        String name = inst != null ? inst.getName() : symbol;
        String category = inst != null && inst.getType() != null
                ? inst.getType().name()
                : InstrumentType.STOCK.name();
        String categoryCode = ValuationSupport.resolveCategoryCode(inst, sampleTx);

        MarketDataPort.MarketQuote quote = ctx.quotes() != null ? ctx.quotes().get(quoteKey) : null;
        boolean priceUnavailable = quote == null || quote.unavailable() || quote.price() == null;
        String quoteCurrency = ValuationSupport.resolveQuoteCurrency(inst, quote, null);
        BigDecimal fxRate = ValuationSupport.requireFxRate(
                quoteCurrency, ctx.baseCurrency(), ctx.fxSeriesByPair(), ctx.today(), true);
        BigDecimal currentPriceNative = !priceUnavailable
                ? quote.price()
                : posResult.averageBuyPriceNative();

        BigDecimal valueBase = posResult.totalQuantity().multiply(currentPriceNative).multiply(fxRate);
        BigDecimal investedBase = posResult.totalCostBase();

        BigDecimal dailyChangeBase = BigDecimal.ZERO;
        BigDecimal dailyChangePct = BigDecimal.ZERO;
        if (!priceUnavailable && quote.previousClose() != null
                && quote.previousClose().compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal diffPerUnit = quote.price().subtract(quote.previousClose());
            dailyChangeBase = posResult.totalQuantity().multiply(diffPerUnit).multiply(fxRate);
            dailyChangePct = diffPerUnit
                    .multiply(BigDecimal.valueOf(100))
                    .divide(quote.previousClose(), 2, RoundingMode.HALF_UP);
        }

        PortfolioLedger.DividendKey divKey = new PortfolioLedger.DividendKey(instrumentId, null);
        BigDecimal posDividends = remainingDividends.remove(divKey);
        if (posDividends == null) {
            posDividends = BigDecimal.ZERO;
        }
        BigDecimal posDividendTax = remainingDividendTax != null ? remainingDividendTax.remove(divKey) : null;

        BigDecimal realizedProfit = posResult.realizedProfitBase();
        TaxPolicy.PositionTaxBreakdown taxBreakdown = TaxPolicy.computePositionTax(
                valueBase, investedBase, realizedProfit, posDividends, posDividendTax, !ctx.shouldApplyTax());
        BigDecimal grossProfitBase = taxBreakdown.grossProfit();
        BigDecimal settledPosTax = taxBreakdown.settledTax();
        BigDecimal profitBase = taxBreakdown.netProfit();
        BigDecimal netVal = taxBreakdown.netValue();

        List<MetricsCalculator.CashFlow> holdingFlows = new ArrayList<>(posResult.cashFlows());
        List<MetricsCalculator.CashFlow> instDivFlows = dividendFlowsByKey.remove(divKey);
        if (instDivFlows != null) {
            holdingFlows.addAll(instDivFlows);
        }
        List<MetricsCalculator.CashFlow> xirrFlows = ValuationSupport.withSettledTaxFlow(
                holdingFlows, settledPosTax, ctx.effectiveNow());
        MetricsCalculator.XirrResult xirrRes = MetricsCalculator.calculateHoldingXirr(
                xirrFlows, netVal.doubleValue(), ctx.effectiveNow(), ValuationSupport.XIRR_MIN_DAYS);

        BigDecimal avgBuyFx = posResult.totalCostNative().compareTo(BigDecimal.ZERO) > 0
                ? posResult.totalCostBase().divide(posResult.totalCostNative(), 8, RoundingMode.HALF_UP)
                : fxRate;
        FxDecompositionDto parentDecomp = computeFxDecomposition(
                quoteCurrency, ctx.baseCurrency(), posResult.averageBuyPriceNative(), currentPriceNative, avgBuyFx, fxRate);

        List<ValuationSubPositionDto> subPositions = buildSubPositions(
                posResult.activeLots(), symbol, quoteCurrency, ctx.baseCurrency(),
                currentPriceNative, fxRate, ctx.shouldApplyTax(), ctx.effectiveNow());

        acc.grossTotalValue = acc.grossTotalValue.add(valueBase);
        acc.totalEquityValue = acc.totalEquityValue.add(valueBase);
        acc.totalInvested = acc.totalInvested.add(investedBase);
        acc.totalDailyChange = acc.totalDailyChange.add(dailyChangeBase);
        acc.totalRealizedProfit = acc.totalRealizedProfit.add(realizedProfit);

        acc.rawPositions.add(new ValuationPositionDto(
                instrumentId.toString(),
                instrumentId,
                symbol,
                name,
                category,
                categoryCode,
                quoteCurrency,
                ValuationSupport.scale(posResult.totalQuantity(), 6),
                ValuationSupport.scale(currentPriceNative, 4),
                ValuationSupport.scale(posResult.averageBuyPriceNative(), 4),
                ValuationSupport.scale(valueBase, 2),
                ValuationSupport.scale(investedBase, 2),
                ValuationSupport.scale(profitBase, 2),
                ValuationSupport.scale(grossProfitBase, 2),
                ValuationSupport.pct(profitBase, investedBase),
                ValuationSupport.scale(dailyChangeBase, 2),
                dailyChangePct,
                BigDecimal.ZERO,
                ValuationSupport.toXirrPct(xirrRes),
                ValuationSupport.scale(posDividends, 2),
                ValuationSupport.scale(realizedProfit, 2),
                null, null, null, null, null,
                priceUnavailable,
                false,
                "PURCHASES",
                parentDecomp,
                subPositions));

        acc.rawEquityWeights.add(new EquityWeightDto(
                symbol,
                name,
                categoryCode,
                quoteCurrency,
                ValuationSupport.scale(valueBase, 2),
                BigDecimal.ZERO));
    }

    private static List<ValuationSubPositionDto> buildSubPositions(
            List<PortfolioLedger.FifoLot> lots,
            String symbol,
            String quoteCurrency,
            String baseCurrency,
            BigDecimal currentPriceNative,
            BigDecimal fxRate,
            boolean shouldApplyTax,
            LocalDateTime effectiveNow) {
        List<ValuationSubPositionDto> subPositions = new ArrayList<>(lots.size());
        for (PortfolioLedger.FifoLot lot : lots) {
            BigDecimal lotValBase = lot.remainingQuantity().multiply(currentPriceNative).multiply(fxRate);
            BigDecimal lotCostBase = lot.remainingCostBase();
            BigDecimal lotGrossProfit = lotValBase.subtract(lotCostBase);
            BigDecimal lotTax = TaxPolicy.computeBelka(lotGrossProfit, !shouldApplyTax);
            BigDecimal lotProfit = lotGrossProfit.subtract(lotTax);
            BigDecimal lotNetVal = lotValBase.subtract(lotTax).max(BigDecimal.ZERO);

            MetricsCalculator.XirrResult subXirr = MetricsCalculator.calculateHoldingXirr(
                    List.of(new MetricsCalculator.CashFlow(-lotCostBase.doubleValue(), lot.executedAt())),
                    lotNetVal.doubleValue(),
                    effectiveNow,
                    ValuationSupport.XIRR_MIN_DAYS);

            BigDecimal lotBuyFx = lot.initialCostNative().compareTo(BigDecimal.ZERO) > 0
                    ? lot.initialCostBase().divide(lot.initialCostNative(), 8, RoundingMode.HALF_UP)
                    : fxRate;
            FxDecompositionDto lotDecomp = computeFxDecomposition(
                    quoteCurrency, baseCurrency, lot.resolvedNativeBuyPrice(), currentPriceNative, lotBuyFx, fxRate);

            subPositions.add(new ValuationSubPositionDto(
                    lot.txId() != null ? lot.txId().toString() : symbol + "-" + lot.executedAt(),
                    lot.txId(),
                    lot.executedAt() != null ? lot.executedAt().toLocalDate().toString() : "",
                    lot.executedAt() != null ? lot.executedAt().toLocalDate() : null,
                    ValuationSupport.scale(lot.remainingQuantity(), 6),
                    ValuationSupport.scale(lot.txPrice(), 4),
                    lot.txCurrency() != null ? lot.txCurrency().name() : quoteCurrency,
                    BigDecimal.ZERO,
                    ValuationSupport.scale(lotCostBase, 2),
                    ValuationSupport.scale(lotValBase, 2),
                    ValuationSupport.scale(lotProfit, 2),
                    ValuationSupport.pct(lotProfit, lotCostBase),
                    ValuationSupport.toXirrPct(subXirr),
                    ValuationSupport.scale(currentPriceNative, 4),
                    ValuationSupport.scale(lot.resolvedNativeBuyPrice(), 4),
                    null, null,
                    lotDecomp));
        }
        return subPositions;
    }

    private static FxDecompositionDto computeFxDecomposition(
            String quoteCurrency,
            String baseCurrency,
            BigDecimal buyPrice,
            BigDecimal currentPrice,
            BigDecimal buyFx,
            BigDecimal currentFx) {
        if (quoteCurrency == null || quoteCurrency.equalsIgnoreCase(baseCurrency)
                || buyPrice == null || currentPrice == null || buyFx == null || currentFx == null) {
            return null;
        }
        MetricsCalculator.FxDecomposition decomp = MetricsCalculator.calculateFxDecomposition(
                buyPrice.doubleValue(),
                currentPrice.doubleValue(),
                buyFx.doubleValue(),
                currentFx.doubleValue());
        if (decomp == null) {
            return null;
        }
        return new FxDecompositionDto(
                quoteCurrency,
                baseCurrency,
                ValuationSupport.bd(decomp.marketReturnPct(), 2),
                ValuationSupport.bd(decomp.fxReturnPct(), 2));
    }
}
