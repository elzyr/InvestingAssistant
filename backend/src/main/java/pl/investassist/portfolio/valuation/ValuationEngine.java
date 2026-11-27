package pl.investassist.portfolio.valuation;

import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.EquityWeightDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ProjectionSeedDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationPositionDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationSummaryDto;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;
import pl.investassist.portfolio.port.MarketDataPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;

public final class ValuationEngine {

    private ValuationEngine() {
    }

    static final class Accumulator {
        final List<MetricsCalculator.CostLot> activeCostLots = new ArrayList<>();
        final List<ValuationPositionDto> rawPositions = new ArrayList<>();
        final List<EquityWeightDto> rawEquityWeights = new ArrayList<>();
        final Map<String, List<BondPositionBuilder.BondLotEntry>> bondLotsByFamily = new LinkedHashMap<>();
        final Map<String, BigDecimal> closedBondRealizedByFamily = new LinkedHashMap<>();
        final Map<String, List<MetricsCalculator.CashFlow>> bondFlowsByFamily = new LinkedHashMap<>();
        final Map<String, List<UUID>> bondInstrumentIdsByFamily = new LinkedHashMap<>();
        final List<TaxPolicy.Art30aPayoutEvent> completedBondArt30aEvents = new ArrayList<>();
        final List<MetricsCalculator.CashFlow> completedBondCouponFlows = new ArrayList<>();
        final Map<String, BigDecimal> completedBondCouponsByFamily = new LinkedHashMap<>();
        final Map<String, List<MetricsCalculator.CashFlow>> completedBondCouponFlowsByFamily = new LinkedHashMap<>();
        final Map<String, BigDecimal> completedBondCouponTaxByFamily = new LinkedHashMap<>();
        final Map<String, List<MetricsCalculator.CashFlow>> completedBondCouponTaxFlowsByFamily = new LinkedHashMap<>();
        BigDecimal completedBondCouponsBase = BigDecimal.ZERO;
        BigDecimal grossTotalValue = BigDecimal.ZERO;
        BigDecimal totalInvested = BigDecimal.ZERO;
        BigDecimal totalEquityValue = BigDecimal.ZERO;
        BigDecimal totalBondValue = BigDecimal.ZERO;
        BigDecimal totalDailyChange = BigDecimal.ZERO;
        BigDecimal totalRealizedProfit = BigDecimal.ZERO;
        BigDecimal weightedBondYieldNumerator = BigDecimal.ZERO;
        BigDecimal weightedBondYieldDenominator = BigDecimal.ZERO;
    }

    public static PortfolioValuationResponse evaluate(
            Portfolio portfolio,
            List<Transaction> transactions,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            BigDecimal currentCpiPct,
            TaxMode requestedTaxMode,
            LocalDateTime now) {
        return evaluatePrecomputed(
                portfolio, transactions, null, instruments, quotes, fxSeriesByPair,
                currentCpiPct, Map.of(), new TreeMap<>(), requestedTaxMode, now);
    }

    public static PortfolioValuationResponse evaluate(
            Portfolio portfolio,
            List<Transaction> transactions,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            BigDecimal currentCpiPct,
            Map<Integer, Map<Integer, BigDecimal>> monthlyCpiByYear,
            NavigableMap<LocalDate, BigDecimal> nbpReferenceRates,
            TaxMode requestedTaxMode,
            LocalDateTime now) {
        return evaluatePrecomputed(
                portfolio, transactions, null, instruments, quotes, fxSeriesByPair,
                currentCpiPct, monthlyCpiByYear, nbpReferenceRates, requestedTaxMode, now);
    }

    public static PortfolioValuationResponse evaluatePrecomputed(
            Portfolio portfolio,
            List<Transaction> transactions,
            PortfolioLedger.IncrementalLedger precomputedLedger,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            BigDecimal currentCpiPct,
            TaxMode requestedTaxMode,
            LocalDateTime now) {
        return evaluatePrecomputed(
                portfolio, transactions, precomputedLedger, instruments, quotes, fxSeriesByPair,
                currentCpiPct, Map.of(), new TreeMap<>(), requestedTaxMode, now);
    }

    public static PortfolioValuationResponse evaluatePrecomputed(
            Portfolio portfolio,
            List<Transaction> transactions,
            PortfolioLedger.IncrementalLedger precomputedLedger,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            BigDecimal currentCpiPct,
            Map<Integer, Map<Integer, BigDecimal>> monthlyCpiByYear,
            NavigableMap<LocalDate, BigDecimal> nbpReferenceRates,
            TaxMode requestedTaxMode,
            LocalDateTime now) {
        TaxMode taxMode = requestedTaxMode != null ? requestedTaxMode : TaxMode.NETTO;
        boolean shouldApplyTax = TaxPolicy.shouldApplyTax(taxMode);
        String baseCurrency = portfolio.getBaseCurrency() != null
                ? portfolio.getBaseCurrency().name()
                : "PLN";
        LocalDateTime effectiveNow = now != null ? now : LocalDateTime.now();

        List<Transaction> safeTxs = transactions != null ? transactions : List.of();
        Map<UUID, Instrument> safeInstruments = instruments != null ? instruments : Map.of();
        List<Transaction> orderedTxs = precomputedLedger != null
                ? safeTxs
                : safeTxs.stream().sorted(PortfolioLedger.TX_ORDER).toList();
        PortfolioLedger.IncrementalLedger ledger = precomputedLedger;
        if (ledger == null) {
            ledger = new PortfolioLedger.IncrementalLedger(safeInstruments);
            for (Transaction tx : orderedTxs) {
                ledger.apply(tx);
            }
        }
        PortfolioLedger.PortfolioReplay replay = ledger.snapshot();

        ValuationContext ctx = new ValuationContext(
                safeInstruments, quotes, Map.of(), fxSeriesByPair,
                currentCpiPct,
                monthlyCpiByYear != null ? monthlyCpiByYear : Map.of(),
                nbpReferenceRates != null ? nbpReferenceRates : new TreeMap<>(),
                baseCurrency, shouldApplyTax, effectiveNow.toLocalDate(), effectiveNow);

        Map<PortfolioLedger.DividendKey, List<MetricsCalculator.CashFlow>> dividendFlowsByKey = new HashMap<>();
        Map<PortfolioLedger.DividendKey, List<MetricsCalculator.CashFlow>> dividendTaxFlowsByKey = new HashMap<>();
        Map<UUID, Transaction> sampleTxByInstrument = new HashMap<>();
        for (Transaction tx : orderedTxs) {
            if (tx.getInstrumentId() == null) {
                continue;
            }
            sampleTxByInstrument.putIfAbsent(tx.getInstrumentId(), tx);
            if (tx.getTransactionType() == TransactionType.DIVIDEND && tx.getExecutedAt() != null) {
                Instrument inst = safeInstruments.get(tx.getInstrumentId());
                boolean isBond = BondValuationEngine.isBondTransaction(tx, inst);
                PortfolioLedger.DividendKey divKey = PortfolioLedger.resolveDividendKey(tx, isBond);
                BigDecimal divBase = PortfolioLedger.resolveProceedsOrDividendBase(tx);
                dividendFlowsByKey
                        .computeIfAbsent(divKey, ignored -> new ArrayList<>())
                        .add(new MetricsCalculator.CashFlow(divBase.doubleValue(), tx.getExecutedAt()));
                BigDecimal divTax = TaxPolicy.computeArt30aTotalTax(divBase, tx.getWithholdingTaxPct(), false);
                if (divTax.compareTo(BigDecimal.ZERO) > 0) {
                    dividendTaxFlowsByKey
                            .computeIfAbsent(divKey, ignored -> new ArrayList<>())
                            .add(new MetricsCalculator.CashFlow(-divTax.doubleValue(), tx.getExecutedAt()));
                }
            }
        }

        Map<PortfolioLedger.DividendKey, BigDecimal> remainingDividends =
                new LinkedHashMap<>(replay.dividendsByKey());
        Map<PortfolioLedger.DividendKey, BigDecimal> remainingDividendTax =
                replay.dividendTaxByKey() != null
                        ? new LinkedHashMap<>(replay.dividendTaxByKey())
                        : new LinkedHashMap<>();
        Accumulator acc = new Accumulator();

        for (Map.Entry<PortfolioLedger.PositionKey, PortfolioLedger.PositionReplayResult> entry :
                replay.positions().entrySet()) {
            PortfolioLedger.PositionKey key = entry.getKey();
            PortfolioLedger.PositionReplayResult posResult = entry.getValue();
            UUID instrumentId = key.instrumentId();
            Instrument inst = safeInstruments.get(instrumentId);
            Transaction sampleTx = sampleTxByInstrument.get(instrumentId);
            boolean isBond = BondValuationEngine.isBondPosition(
                    inst,
                    key.seriesName(),
                    posResult.hasBondMetadata() || BondValuationEngine.hasBondMetadata(sampleTx));

            if (posResult.totalQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                if (isBond) {
                    BondPositionBuilder.collectClosedBondPosition(acc, key, posResult, inst, ctx);
                } else {
                    BigDecimal eqRealized = posResult.realizedProfitBase();
                    acc.totalRealizedProfit = acc.totalRealizedProfit.add(eqRealized);
                }
                continue;
            }

            for (PortfolioLedger.FifoLot lot : posResult.activeLots()) {
                acc.activeCostLots.add(new MetricsCalculator.CostLot(
                        lot.remainingCostBase().doubleValue(), lot.executedAt()));
            }

            if (isBond) {
                BondPositionBuilder.collectBondLots(acc, key, posResult, inst, ctx);
            } else {
                EquityPositionBuilder.buildEquityPosition(
                        acc, instrumentId, posResult, inst, sampleTx,
                        dividendFlowsByKey, remainingDividends, remainingDividendTax, ctx);
            }
        }

        BondPositionBuilder.buildBondPositions(
                acc, dividendFlowsByKey, dividendTaxFlowsByKey, remainingDividends, remainingDividendTax, ctx);

        List<ValuationPositionDto> finalPositions = acc.rawPositions.stream()
                .map(p -> p.withAllocation(acc.grossTotalValue))
                .sorted(Comparator.comparing(ValuationPositionDto::valueBase).reversed())
                .toList();

        List<EquityWeightDto> equityWeights = acc.rawEquityWeights.stream()
                .map(w -> w.withWeightPct(acc.totalEquityValue))
                .toList();

        BigDecimal grossTotalValue = acc.grossTotalValue;
        BigDecimal totalInvested = acc.totalInvested;
        BigDecimal totalDividends = replay.totalDividendsBase().add(acc.completedBondCouponsBase);
        BigDecimal totalRealizedProfit = acc.totalRealizedProfit;
        List<TaxPolicy.Art30aPayoutEvent> allArt30aEvents = new ArrayList<>(replay.art30aEvents());
        allArt30aEvents.addAll(acc.completedBondArt30aEvents);
        TaxPolicy.PortfolioTaxResult portfolioTax = TaxPolicy.computePortfolioTax(
                replay.disposalEvents(),
                allArt30aEvents,
                grossTotalValue,
                totalInvested,
                !shouldApplyTax);
        BigDecimal totalBelkaTax = portfolioTax.totalTax();
        BigDecimal displayPortfolioValue = shouldApplyTax
                ? grossTotalValue.subtract(portfolioTax.hypotheticalOpenTax())
                : grossTotalValue;
        BigDecimal grossProfit = grossTotalValue.subtract(totalInvested)
                .add(totalDividends)
                .add(totalRealizedProfit);
        BigDecimal netProfit = grossProfit.subtract(totalBelkaTax);
        BigDecimal prevGrossVal = grossTotalValue.subtract(acc.totalDailyChange);

        List<MetricsCalculator.CashFlow> rawPortfolioFlows = new ArrayList<>(replay.portfolioCashFlows());
        rawPortfolioFlows.addAll(acc.completedBondCouponFlows);
        List<MetricsCalculator.CashFlow> portfolioXirrFlows = new ArrayList<>(rawPortfolioFlows);
        if (shouldApplyTax && portfolioTax.settledTaxFlows() != null) {
            portfolioXirrFlows.addAll(portfolioTax.settledTaxFlows());
        }
        MetricsCalculator.XirrResult portfolioXirr = MetricsCalculator.calculateHoldingXirr(
                portfolioXirrFlows, displayPortfolioValue.doubleValue(), effectiveNow, ValuationSupport.XIRR_MIN_DAYS);

        MetricsCalculator.RealMetrics realMetrics = MetricsCalculator.calculatePortfolioRealMetrics(
                acc.activeCostLots,
                rawPortfolioFlows,
                monthlyCpiByYear,
                displayPortfolioValue.doubleValue(),
                currentCpiPct != null ? currentCpiPct.doubleValue() : null,
                portfolioXirr.xirr(),
                effectiveNow);

        BigDecimal scaledCpi = currentCpiPct != null ? ValuationSupport.scale(currentCpiPct, 2) : null;
        ValuationSummaryDto summary = new ValuationSummaryDto(
                ValuationSupport.scale(displayPortfolioValue, 2),
                ValuationSupport.scale(grossTotalValue, 2),
                ValuationSupport.scale(totalInvested, 2),
                ValuationSupport.scale(netProfit, 2),
                ValuationSupport.pct(netProfit, totalInvested),
                ValuationSupport.scale(grossProfit, 2),
                ValuationSupport.scale(totalRealizedProfit, 2),
                ValuationSupport.scale(totalBelkaTax, 2),
                ValuationSupport.scale(totalDividends, 2),
                ValuationSupport.scale(acc.totalDailyChange, 2),
                ValuationSupport.pct(acc.totalDailyChange, prevGrossVal),
                ValuationSupport.toXirrPct(portfolioXirr),
                realMetrics != null ? ValuationSupport.bd(realMetrics.realProfitAmount(), 2) : null,
                realMetrics != null ? ValuationSupport.bd(realMetrics.realProfitPercent(), 2) : null,
                realMetrics != null && realMetrics.realXirr() != null
                        ? ValuationSupport.bd(realMetrics.realXirr() * 100.0, 2)
                        : null,
                scaledCpi,
                finalPositions.size());

        BigDecimal weightedBondYieldPct = acc.weightedBondYieldDenominator.compareTo(BigDecimal.ZERO) > 0
                ? acc.weightedBondYieldNumerator.divide(acc.weightedBondYieldDenominator, 2, RoundingMode.HALF_UP)
                : null;

        ProjectionSeedDto projectionSeed = new ProjectionSeedDto(
                ValuationSupport.scale(grossTotalValue, 2),
                ValuationSupport.scale(acc.totalEquityValue, 2),
                ValuationSupport.scale(acc.totalBondValue, 2),
                ValuationSupport.scale(totalInvested, 2),
                weightedBondYieldPct,
                scaledCpi,
                shouldApplyTax ? TaxPolicy.BELKA_RATE_BD : BigDecimal.ZERO,
                equityWeights);

        return new PortfolioValuationResponse(
                portfolio.getId(),
                baseCurrency,
                taxMode,
                summary,
                finalPositions,
                projectionSeed);
    }
}
