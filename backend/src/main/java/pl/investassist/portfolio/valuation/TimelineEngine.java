package pl.investassist.portfolio.valuation;

import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse.ProfitContributorDto;
import pl.investassist.portfolio.dto.response.PortfolioTimelineResponse.TimelinePointDto;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.port.MarketDataPort;
import pl.investassist.portfolio.valuation.PortfolioStepValuator.StepGroupContributor;
import pl.investassist.portfolio.valuation.PortfolioStepValuator.StepValuationResult;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class TimelineEngine {

    private static final int MAX_POINTS = 120;
    private static final int TOP_CONTRIBUTORS_LIMIT = 5;

    private TimelineEngine() {
    }

    private record StepRecord(
            LocalDate date,
            StepValuationResult step,
            BigDecimal returnPct,
            BigDecimal stepGain
    ) {
    }

    private record ContribRow(
            StepGroupContributor meta,
            BigDecimal valuation,
            BigDecimal cost,
            BigDecimal netProfit,
            boolean isOther
    ) {
    }

    public static PortfolioTimelineResponse build(
            Portfolio portfolio,
            List<Transaction> transactions,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.PriceHistory> histories,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            BigDecimal currentCpiPct,
            Timeframe requestedTimeframe,
            TaxMode requestedTaxMode,
            LocalDateTime now) {
        return build(
                portfolio, transactions, instruments, quotes, histories, fxSeriesByPair,
                currentCpiPct, Map.of(), new java.util.TreeMap<>(), requestedTimeframe, requestedTaxMode, now);
    }

    public static PortfolioTimelineResponse build(
            Portfolio portfolio,
            List<Transaction> transactions,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.PriceHistory> histories,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            BigDecimal currentCpiPct,
            Map<Integer, Map<Integer, BigDecimal>> monthlyCpiByYear,
            java.util.NavigableMap<LocalDate, BigDecimal> nbpReferenceRates,
            Timeframe requestedTimeframe,
            TaxMode requestedTaxMode,
            LocalDateTime now) {
        Timeframe timeframe = requestedTimeframe != null ? requestedTimeframe : Timeframe.YTD;
        TaxMode taxMode = requestedTaxMode != null ? requestedTaxMode : TaxMode.NETTO;
        boolean shouldApplyTax = TaxPolicy.shouldApplyTax(taxMode);
        String baseCurrency = portfolio.getBaseCurrency() != null
                ? portfolio.getBaseCurrency().name()
                : "PLN";
        LocalDateTime effectiveNow = now != null ? now : LocalDateTime.now();
        LocalDate today = effectiveNow.toLocalDate();

        List<Transaction> sorted = (transactions != null ? transactions : List.<Transaction>of()).stream()
                .filter(tx -> tx.getExecutedAt() != null)
                .sorted(PortfolioLedger.TX_ORDER)
                .toList();

        if (sorted.isEmpty()) {
            return new PortfolioTimelineResponse(
                    portfolio.getId(),
                    baseCurrency,
                    timeframe,
                    taxMode,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    List.of(),
                    List.of());
        }

        LocalDate earliestTxDate = sorted.getFirst().getExecutedAt().toLocalDate();
        LocalDate startDate = timeframe.resolveStartDate(today, earliestTxDate);
        boolean hasPriorTransactions = earliestTxDate.isBefore(startDate);
        List<LocalDate> sampleDates = buildSampleDates(startDate, today);
        Set<LocalDate> sampleDateSet = new java.util.HashSet<>(sampleDates);
        java.util.NavigableSet<LocalDate> evalDates = new java.util.TreeSet<>(sampleDates);
        for (Transaction tx : sorted) {
            LocalDate d = tx.getExecutedAt().toLocalDate();
            if (!d.isBefore(startDate) && !d.isAfter(today)) {
                evalDates.add(d);
            }
        }

        Map<UUID, Instrument> safeInstruments = instruments != null ? instruments : Map.of();
        ValuationContext baseCtx = new ValuationContext(
                safeInstruments, quotes, histories, fxSeriesByPair,
                currentCpiPct,
                monthlyCpiByYear != null ? monthlyCpiByYear : Map.of(),
                nbpReferenceRates != null ? nbpReferenceRates : new java.util.TreeMap<>(),
                baseCurrency, shouldApplyTax, today, effectiveNow);
        PortfolioLedger.IncrementalLedger ledger = new PortfolioLedger.IncrementalLedger(safeInstruments);
        int txIdx = 0;

        List<StepRecord> stepRecords = new ArrayList<>(sampleDates.size());
        List<MetricsCalculator.TwrPeriod> twrPeriods = new ArrayList<>();

        BigDecimal prevDisplayValuation = BigDecimal.ZERO;
        BigDecimal prevExtFlow = BigDecimal.ZERO;
        BigDecimal prevSettledTax = BigDecimal.ZERO;
        BigDecimal prevNetProfit = BigDecimal.ZERO;
        boolean anyDegraded = false;
        int evalIdx = 0;

        for (LocalDate stepDate : evalDates) {
            boolean isToday = stepDate.equals(today);

            while (txIdx < sorted.size() && !sorted.get(txIdx).getExecutedAt().toLocalDate().isAfter(stepDate)) {
                ledger.apply(sorted.get(txIdx++));
            }

            StepValuationResult step = PortfolioStepValuator.evaluate(
                    ledger,
                    baseCtx.forStep(stepDate, currentCpiPct),
                    isToday,
                    true,
                    false);
            anyDegraded |= step.degraded();

            BigDecimal displayValuation = step.displayValuation();
            BigDecimal extFlow = ledger.cumulativeExternalCashFlow().subtract(step.completedBondCouponsBase());
            BigDecimal settledTax = step.settledTax();

            if (evalIdx > 0 || !hasPriorTransactions) {
                twrPeriods.add(MetricsCalculator.stepTwrPeriod(
                        prevDisplayValuation, displayValuation,
                        prevExtFlow, extFlow,
                        prevSettledTax, settledTax));
            }
            if (sampleDateSet.contains(stepDate)) {
                BigDecimal returnPct = ValuationSupport.pct(step.netProfit(), step.totalInvested());
                BigDecimal stepGain = stepRecords.isEmpty() ? BigDecimal.ZERO : step.netProfit().subtract(prevNetProfit);
                stepRecords.add(new StepRecord(stepDate, step, returnPct, stepGain));
                prevNetProfit = step.netProfit();
            }
            prevDisplayValuation = displayValuation;
            prevExtFlow = extFlow;
            prevSettledTax = settledTax;
            evalIdx++;
        }

        List<TimelinePointDto> finalPoints = buildTimelinePoints(stepRecords);
        List<ProfitContributorDto> latestContributors = finalPoints.isEmpty()
                ? List.of()
                : finalPoints.getLast().contributors();

        BigDecimal periodProfit = finalPoints.size() >= 2
                ? finalPoints.getLast().netProfit().subtract(finalPoints.getFirst().netProfit())
                : (finalPoints.isEmpty() ? BigDecimal.ZERO : finalPoints.getFirst().netProfit());
        BigDecimal firstVal = !finalPoints.isEmpty() ? finalPoints.getFirst().value() : BigDecimal.ZERO;
        BigDecimal periodProfitPct = firstVal.compareTo(BigDecimal.ZERO) > 0
                ? ValuationSupport.pct(periodProfit, firstVal)
                : (!finalPoints.isEmpty() ? finalPoints.getLast().returnPct() : BigDecimal.ZERO);
        BigDecimal periodTwrPct = ValuationSupport.bd(MetricsCalculator.calculateTwr(twrPeriods) * 100.0, 2);

        return new PortfolioTimelineResponse(
                portfolio.getId(),
                baseCurrency,
                timeframe,
                taxMode,
                ValuationSupport.scale(periodProfit, 2),
                periodProfitPct,
                periodTwrPct,
                finalPoints,
                latestContributors,
                anyDegraded);
    }

    private static List<LocalDate> buildSampleDates(LocalDate startDate, LocalDate endDate) {
        long totalDays = ChronoUnit.DAYS.between(startDate, endDate);
        if (totalDays <= 0) {
            return List.of(endDate);
        }
        Set<LocalDate> dates = new LinkedHashSet<>();
        int steps = (int) Math.min(MAX_POINTS - 1, totalDays);
        for (int i = 0; i < steps; i++) {
            long offset = Math.round((double) i * totalDays / steps);
            dates.add(startDate.plusDays(offset));
        }
        dates.add(endDate);
        return new ArrayList<>(dates);
    }

    private static List<TimelinePointDto> buildTimelinePoints(List<StepRecord> records) {
        if (records.isEmpty()) {
            return List.of();
        }
        Map<String, StepGroupContributor> metaByKey = new LinkedHashMap<>();
        Map<String, BigDecimal> maxAbsProfitByKey = new HashMap<>();
        for (StepRecord rec : records) {
            for (StepGroupContributor c : rec.step().contributorsByKey().values()) {
                metaByKey.putIfAbsent(c.key(), c);
                maxAbsProfitByKey.merge(c.key(), c.netProfit().abs(), BigDecimal::max);
            }
        }
        Map<String, StepGroupContributor> latestMap = records.getLast().step().contributorsByKey();
        List<String> sortedKeys = new ArrayList<>(metaByKey.keySet());
        sortedKeys.sort(Comparator
                .<String, BigDecimal>comparing(k -> {
                    StepGroupContributor latest = latestMap.get(k);
                    return latest != null ? latest.netProfit().abs() : BigDecimal.ZERO;
                })
                .reversed()
                .thenComparing(Comparator.<String, BigDecimal>comparing(
                        k -> maxAbsProfitByKey.getOrDefault(k, BigDecimal.ZERO)).reversed())
                .thenComparing(Comparator.naturalOrder()));

        boolean hasOther = sortedKeys.size() > TOP_CONTRIBUTORS_LIMIT;
        List<String> topKeys = hasOther ? sortedKeys.subList(0, TOP_CONTRIBUTORS_LIMIT) : sortedKeys;
        List<String> otherKeys = hasOther ? sortedKeys.subList(TOP_CONTRIBUTORS_LIMIT, sortedKeys.size()) : List.of();
        StepGroupContributor otherMeta = new StepGroupContributor(
                "OTHER", "OTHER", "OTHER", "OTHER", BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);

        List<TimelinePointDto> result = new ArrayList<>(records.size());
        for (StepRecord rec : records) {
            StepValuationResult step = rec.step();
            Map<String, StepGroupContributor> map = step.contributorsByKey();
            BigDecimal pointCost = step.totalInvested();

            List<ContribRow> rows = new ArrayList<>();
            for (String k : topKeys) {
                StepGroupContributor c = map.get(k);
                StepGroupContributor meta = metaByKey.get(k);
                rows.add(new ContribRow(
                        meta,
                        c != null ? c.valuationBase() : BigDecimal.ZERO,
                        c != null ? c.costBase() : BigDecimal.ZERO,
                        c != null ? c.netProfit() : BigDecimal.ZERO,
                        false));
            }

            if (hasOther) {
                BigDecimal oVal = BigDecimal.ZERO;
                BigDecimal oCost = BigDecimal.ZERO;
                BigDecimal oNet = BigDecimal.ZERO;
                for (String ok : otherKeys) {
                    StepGroupContributor c = map.get(ok);
                    if (c != null) {
                        oVal = oVal.add(c.valuationBase());
                        oCost = oCost.add(c.costBase());
                        oNet = oNet.add(c.netProfit());
                    }
                }
                rows.add(new ContribRow(otherMeta, oVal, oCost, oNet, true));
            }

            BigDecimal sumAbs = rows.stream()
                    .map(r -> r.netProfit().abs())
                    .reduce(BigDecimal.ZERO, BigDecimal::add);

            List<ProfitContributorDto> contributors = new ArrayList<>(rows.size());
            for (ContribRow row : rows) {
                BigDecimal retContrib = ValuationSupport.pct(row.netProfit(), pointCost, 2);
                BigDecimal sharePct = ValuationSupport.pct(row.netProfit(), sumAbs, 1);
                contributors.add(new ProfitContributorDto(
                        row.meta().key(),
                        row.meta().name(),
                        row.meta().symbol(),
                        row.meta().category(),
                        ValuationSupport.scale(row.valuation(), 2),
                        ValuationSupport.scale(row.cost(), 2),
                        ValuationSupport.scale(row.netProfit(), 2),
                        retContrib,
                        sharePct,
                        row.isOther()));
            }

            result.add(new TimelinePointDto(
                    rec.date(),
                    ValuationSupport.scale(step.displayValuation(), 2),
                    ValuationSupport.scale(step.totalInvested(), 2),
                    ValuationSupport.scale(step.netProfit(), 2),
                    rec.returnPct(),
                    ValuationSupport.scale(rec.stepGain(), 2),
                    ValuationSupport.scale(step.equityValue(), 2),
                    ValuationSupport.scale(step.bondValue(), 2),
                    contributors));
        }
        return result;
    }
}
