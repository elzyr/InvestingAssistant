package pl.investassist.portfolio.valuation;

import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.dto.response.AnnualReportResponse;
import pl.investassist.portfolio.dto.response.AnnualReportResponse.MonthStatus;
import pl.investassist.portfolio.dto.response.AnnualReportResponse.MonthlyCategoryDto;
import pl.investassist.portfolio.dto.response.AnnualReportResponse.MonthlyPositionDto;
import pl.investassist.portfolio.dto.response.AnnualReportResponse.MonthlyReportDto;
import pl.investassist.portfolio.entity.Portfolio;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.port.MarketDataPort;
import pl.investassist.portfolio.valuation.PortfolioStepValuator.StepCategoryTotals;
import pl.investassist.portfolio.valuation.PortfolioStepValuator.StepGroupContributor;
import pl.investassist.portfolio.valuation.PortfolioStepValuator.StepValuationResult;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;

public final class AnnualReportEngine {

    private AnnualReportEngine() {
    }

    private record CpiResolution(BigDecimal cpi, boolean isFallback) {
    }

    private record RecordedMonthEntry(
            MonthlyReportDto dto,
            BigDecimal cumulativeExtFlow,
            BigDecimal settledTax
    ) {
    }

    public static AnnualReportResponse build(
            Portfolio portfolio,
            List<Transaction> transactions,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.PriceHistory> histories,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            MarketDataPort.InflationSnapshot inflation,
            int year,
            TaxMode requestedTaxMode,
            LocalDateTime now) {
        return build(
                portfolio, transactions, instruments, quotes, histories, fxSeriesByPair,
                inflation, new java.util.TreeMap<>(), year, requestedTaxMode, now);
    }

    public static AnnualReportResponse build(
            Portfolio portfolio,
            List<Transaction> transactions,
            Map<UUID, Instrument> instruments,
            Map<String, MarketDataPort.MarketQuote> quotes,
            Map<String, MarketDataPort.PriceHistory> histories,
            Map<String, MarketDataPort.FxSeries> fxSeriesByPair,
            MarketDataPort.InflationSnapshot inflation,
            java.util.NavigableMap<LocalDate, BigDecimal> nbpReferenceRates,
            int year,
            TaxMode requestedTaxMode,
            LocalDateTime now) {
        TaxMode taxMode = requestedTaxMode != null ? requestedTaxMode : TaxMode.NETTO;
        boolean shouldApplyTax = TaxPolicy.shouldApplyTax(taxMode);
        String baseCurrency = portfolio.getBaseCurrency() != null
                ? portfolio.getBaseCurrency().name()
                : "PLN";
        LocalDateTime effectiveNow = now != null ? now : LocalDateTime.now();
        LocalDate today = effectiveNow.toLocalDate();
        boolean isCurrentYear = year == today.getYear();

        List<Transaction> sorted = (transactions != null ? transactions : List.<Transaction>of()).stream()
                .filter(tx -> tx.getExecutedAt() != null)
                .sorted(PortfolioLedger.TX_ORDER)
                .toList();

        Set<Integer> yearSet = new TreeSet<>(Comparator.reverseOrder());
        int currentYear = Math.clamp(today.getYear(), 2000, 2100);
        yearSet.add(currentYear);
        if (!sorted.isEmpty()) {
            int minYear = Math.clamp(sorted.getFirst().getExecutedAt().getYear(), 2000, 2100);
            for (int y = minYear; y <= currentYear; y++) {
                yearSet.add(y);
            }
        }
        if (year >= 2000 && year <= 2100) {
            yearSet.add(year);
        }
        List<Integer> availableYears = List.copyOf(yearSet);

        LocalDate earliestTxDate = sorted.isEmpty() ? null : sorted.getFirst().getExecutedAt().toLocalDate();
        Map<UUID, Instrument> safeInstruments = instruments != null ? instruments : Map.of();
        PortfolioLedger.IncrementalLedger ledger = new PortfolioLedger.IncrementalLedger(safeInstruments);
        int txIdx = 0;

        ValuationContext baseCtx = new ValuationContext(
                safeInstruments, quotes, histories, fxSeriesByPair,
                null,
                inflation != null && inflation.monthlyByYear() != null ? inflation.monthlyByYear() : Map.of(),
                nbpReferenceRates != null ? nbpReferenceRates : new java.util.TreeMap<>(),
                baseCurrency, shouldApplyTax, today, effectiveNow);

        LocalDate prevYearDec31 = LocalDate.of(year - 1, 12, 31);
        boolean hasPriorYearBaseline = earliestTxDate != null
                && !earliestTxDate.isAfter(prevYearDec31)
                && !prevYearDec31.isAfter(today);
        BigDecimal baselineValue = BigDecimal.ZERO;
        BigDecimal baselineGrossProfit = BigDecimal.ZERO;
        BigDecimal baselineNetProfit = BigDecimal.ZERO;
        BigDecimal baselineSettledTax = BigDecimal.ZERO;
        BigDecimal baselineExtFlow = BigDecimal.ZERO;
        boolean anyDegraded = false;
        Map<String, BigDecimal> prevPositionNetProfit = new LinkedHashMap<>();

        if (hasPriorYearBaseline) {
            while (txIdx < sorted.size() && !sorted.get(txIdx).getExecutedAt().toLocalDate().isAfter(prevYearDec31)) {
                ledger.apply(sorted.get(txIdx++));
            }
            BigDecimal prevDecCpi = resolveMonthlyCpi(inflation, year - 1, 12).cpi();
            StepValuationResult baselineStep = PortfolioStepValuator.evaluate(
                    ledger,
                    baseCtx.forStep(prevYearDec31, prevDecCpi),
                    false,
                    true,
                    false);
            anyDegraded |= baselineStep.degraded();
            baselineValue = baselineStep.displayValuation();
            baselineGrossProfit = baselineStep.grossProfit();
            baselineNetProfit = baselineStep.netProfit();
            baselineSettledTax = baselineStep.settledTax();
            baselineExtFlow = ledger.cumulativeExternalCashFlow().subtract(baselineStep.completedBondCouponsBase());
            for (StepGroupContributor c : baselineStep.contributorsByKey().values()) {
                prevPositionNetProfit.put(c.key(), ValuationSupport.scale(c.netProfit(), 2));
            }
        }

        List<MonthlyReportDto> rawMonths = new ArrayList<>(12);
        List<RecordedMonthEntry> recordedEntries = new ArrayList<>(12);

        for (int monthIdx = 0; monthIdx < 12; monthIdx++) {
            YearMonth ym = YearMonth.of(year, monthIdx + 1);
            LocalDate monthFirst = ym.atDay(1);
            LocalDate monthEnd = ym.atEndOfMonth();

            if (monthFirst.isAfter(today)) {
                rawMonths.add(emptyMonth(monthIdx, MonthStatus.FUTURE));
                continue;
            }
            if (earliestTxDate == null || monthEnd.isBefore(earliestTxDate)) {
                rawMonths.add(emptyMonth(monthIdx, MonthStatus.BEFORE_PORTFOLIO));
                continue;
            }

            boolean isCurrentMonth = isCurrentYear && (monthIdx + 1 == today.getMonthValue());
            LocalDate targetDate = isCurrentMonth ? today : monthEnd;
            LocalDateTime targetDateTime = isCurrentMonth ? effectiveNow : monthEnd.atTime(23, 59, 59);

            while (txIdx < sorted.size() && !sorted.get(txIdx).getExecutedAt().toLocalDate().isAfter(targetDate)) {
                ledger.apply(sorted.get(txIdx++));
            }

            CpiResolution cpiRes = resolveMonthlyCpi(inflation, year, monthIdx + 1);
            BigDecimal monthInflation = cpiRes.cpi();
            StepValuationResult step = PortfolioStepValuator.evaluate(
                    ledger,
                    baseCtx.forStep(targetDate, monthInflation),
                    isCurrentMonth,
                    true,
                    true);
            anyDegraded |= step.degraded();

            BigDecimal displayVal = step.displayValuation();
            BigDecimal totalInvested = step.totalInvested();
            BigDecimal profitGrossTotal = step.grossProfit();
            BigDecimal profitNetTotal = step.netProfit();

            List<MonthlyCategoryDto> breakdown = new ArrayList<>(step.categoriesByCode().size());
            for (StepCategoryTotals cat : step.categoriesByCode().values()) {
                BigDecimal inv = cat.invested().max(BigDecimal.ZERO);
                BigDecimal val = cat.currentValue().max(BigDecimal.ZERO);
                breakdown.add(new MonthlyCategoryDto(
                        cat.categoryCode(),
                        ValuationSupport.scale(inv, 2),
                        ValuationSupport.scale(val, 2),
                        ValuationSupport.scale(cat.profitGross(), 2),
                        ValuationSupport.scale(cat.profitNet(), 2),
                        ValuationSupport.pct(cat.profitGross(), inv),
                        ValuationSupport.pct(cat.profitNet(), inv),
                        ValuationSupport.pct(val, displayVal)));
            }

            List<MonthlyPositionDto> positions = new ArrayList<>(step.contributorsByKey().size());
            Map<String, BigDecimal> curPositionNetProfit = new LinkedHashMap<>();
            for (StepGroupContributor c : step.contributorsByKey().values()) {
                BigDecimal inv = c.costBase().max(BigDecimal.ZERO);
                BigDecimal val = c.valuationBase().max(BigDecimal.ZERO);
                BigDecimal pGross = ValuationSupport.scale(c.grossProfit(), 2);
                BigDecimal pNet = ValuationSupport.scale(c.netProfit(), 2);
                curPositionNetProfit.put(c.key(), pNet);
                BigDecimal pMom = pNet.subtract(prevPositionNetProfit.getOrDefault(c.key(), BigDecimal.ZERO))
                        .setScale(2, RoundingMode.HALF_UP);
                if (inv.compareTo(BigDecimal.ZERO) == 0
                        && val.compareTo(BigDecimal.ZERO) == 0
                        && pMom.compareTo(BigDecimal.ZERO) == 0) {
                    continue;
                }
                positions.add(new MonthlyPositionDto(
                        c.key(),
                        c.symbol(),
                        c.name(),
                        c.category(),
                        ValuationSupport.scale(inv, 2),
                        ValuationSupport.scale(val, 2),
                        pGross,
                        pNet,
                        pMom,
                        ValuationSupport.pct(c.grossProfit(), inv),
                        ValuationSupport.pct(c.netProfit(), inv),
                        ValuationSupport.pct(val, displayVal)));
            }
            positions.sort(Comparator.comparing(MonthlyPositionDto::currentValue).reversed()
                    .thenComparing(MonthlyPositionDto::symbol));
            prevPositionNetProfit = curPositionNetProfit;

            BigDecimal realProfitPct = null;
            if (monthInflation != null) {
                Double hurdle = null;
                if (inflation != null && inflation.monthlyByYear() != null) {
                    hurdle = MetricsCalculator.compoundCpiPathHurdle(
                            ledger.snapshot().portfolioCashFlows(), inflation.monthlyByYear(), targetDateTime);
                }
                if (hurdle == null) {
                    List<MetricsCalculator.CostLot> activeLots = new ArrayList<>();
                    for (PortfolioLedger.PositionAccumulator acc : ledger.accumulators().values()) {
                        for (PortfolioLedger.FifoLot lot : acc.activeLots()) {
                            activeLots.add(new MetricsCalculator.CostLot(
                                    lot.remainingCostBase().doubleValue(), lot.executedAt()));
                        }
                    }
                    if (!activeLots.isEmpty()) {
                        hurdle = MetricsCalculator.compoundHurdle(
                                activeLots, monthInflation.doubleValue(), targetDateTime);
                    }
                }
                if (hurdle != null && hurdle > 0.0) {
                    double pct = (displayVal.doubleValue() / hurdle - 1.0) * 100.0;
                    realProfitPct = ValuationSupport.bd(pct, 2);
                }
            }

            MonthlyReportDto rawDto = new MonthlyReportDto(
                    monthIdx,
                    MonthStatus.RECORDED,
                    ValuationSupport.scale(displayVal, 2),
                    ValuationSupport.scale(totalInvested, 2),
                    ValuationSupport.scale(profitGrossTotal, 2),
                    ValuationSupport.pct(profitGrossTotal, totalInvested),
                    ValuationSupport.scale(profitNetTotal, 2),
                    ValuationSupport.pct(profitNetTotal, totalInvested),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    monthInflation != null ? ValuationSupport.scale(monthInflation, 1) : null,
                    cpiRes.isFallback(),
                    realProfitPct,
                    breakdown,
                    positions);
            rawMonths.add(rawDto);
            recordedEntries.add(new RecordedMonthEntry(
                    rawDto,
                    ledger.cumulativeExternalCashFlow().subtract(step.completedBondCouponsBase()),
                    step.settledTax()));
        }

        List<MonthlyReportDto> finalMonths = new ArrayList<>(12);
        List<MonthlyReportDto> recordedOnly = new ArrayList<>();
        List<MetricsCalculator.TwrPeriod> subPeriods = new ArrayList<>(recordedEntries.size());

        BigDecimal prevVal = baselineValue;
        BigDecimal prevNetProfit = baselineNetProfit;
        BigDecimal prevExtFlow = baselineExtFlow;
        BigDecimal prevSettledTax = baselineSettledTax;
        boolean hasPrevForMom = hasPriorYearBaseline;
        int recordedCursor = 0;

        for (MonthlyReportDto cur : rawMonths) {
            if (cur.status() != MonthStatus.RECORDED) {
                finalMonths.add(cur);
                continue;
            }
            RecordedMonthEntry recEntry = recordedEntries.get(recordedCursor++);

            BigDecimal momDiff;
            BigDecimal momPct;
            if (hasPrevForMom) {
                momDiff = cur.profitNet().subtract(prevNetProfit).setScale(2, RoundingMode.HALF_UP);
                momPct = prevVal.compareTo(BigDecimal.ZERO) > 0
                        ? ValuationSupport.pct(momDiff, prevVal)
                        : cur.returnNetPct();
            } else {
                momDiff = cur.profitNet();
                momPct = cur.returnNetPct();
            }

            MonthlyReportDto updated = new MonthlyReportDto(
                    cur.monthIndex(),
                    cur.status(),
                    cur.portfolioValue(),
                    cur.investedTotal(),
                    cur.profitGross(),
                    cur.returnGrossPct(),
                    cur.profitNet(),
                    cur.returnNetPct(),
                    momDiff,
                    momPct,
                    cur.cpiInflation(),
                    cur.inflationIsFallback(),
                    cur.realProfitPct(),
                    cur.breakdown(),
                    cur.positions());
            finalMonths.add(updated);
            recordedOnly.add(updated);

            subPeriods.add(MetricsCalculator.stepTwrPeriod(
                    prevVal, cur.portfolioValue(),
                    prevExtFlow, recEntry.cumulativeExtFlow(),
                    prevSettledTax, recEntry.settledTax()));

            prevVal = cur.portfolioValue();
            prevNetProfit = cur.profitNet();
            prevExtFlow = recEntry.cumulativeExtFlow();
            prevSettledTax = recEntry.settledTax();
            hasPrevForMom = true;
        }

        MonthlyReportDto latest = recordedOnly.isEmpty() ? null : recordedOnly.getLast();
        BigDecimal ytdPortfolioValue = latest != null ? latest.portfolioValue() : BigDecimal.ZERO;
        BigDecimal ytdInvested = latest != null ? latest.investedTotal() : BigDecimal.ZERO;
        BigDecimal ytdProfitGross = latest != null ? latest.profitGross().subtract(baselineGrossProfit) : BigDecimal.ZERO;
        BigDecimal ytdProfitNet = latest != null ? latest.profitNet().subtract(baselineNetProfit) : BigDecimal.ZERO;
        BigDecimal latestExtFlow = recordedEntries.isEmpty() ? BigDecimal.ZERO : recordedEntries.getLast().cumulativeExtFlow();
        BigDecimal ytdNetAdditions = latestExtFlow.subtract(baselineExtFlow);
        BigDecimal ytdCapitalBase = baselineValue.compareTo(BigDecimal.ZERO) > 0
                ? baselineValue.add(ytdNetAdditions.max(BigDecimal.ZERO))
                : ytdInvested;
        BigDecimal ytdReturnGrossPct = ValuationSupport.pct(ytdProfitGross, ytdCapitalBase);
        BigDecimal ytdReturnNetPct = ValuationSupport.pct(ytdProfitNet, ytdCapitalBase);
        BigDecimal ytdRealReturnPct = latest != null ? latest.realProfitPct() : null;
        BigDecimal ytdTwrPct = ValuationSupport.bd(MetricsCalculator.calculateTwr(subPeriods) * 100.0, 2);

        List<BigDecimal> cpiValues = recordedOnly.stream()
                .map(MonthlyReportDto::cpiInflation)
                .filter(c -> c != null)
                .toList();
        BigDecimal avgCpi = cpiValues.isEmpty()
                ? null
                : cpiValues.stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(cpiValues.size()), 1, RoundingMode.HALF_UP);

        return new AnnualReportResponse(
                portfolio.getId(),
                baseCurrency,
                year,
                taxMode,
                isCurrentYear,
                availableYears,
                ytdPortfolioValue,
                ytdInvested,
                ytdProfitGross,
                ytdReturnGrossPct,
                ytdProfitNet,
                ytdReturnNetPct,
                ytdTwrPct,
                ytdRealReturnPct,
                avgCpi,
                finalMonths,
                anyDegraded);
    }

    private static MonthlyReportDto emptyMonth(int monthIndex, MonthStatus status) {
        return new MonthlyReportDto(
                monthIndex,
                status,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                BigDecimal.ZERO,
                null,
                false,
                null,
                List.of(),
                List.of());
    }

    private static CpiResolution resolveMonthlyCpi(
            MarketDataPort.InflationSnapshot inflation,
            int year,
            int monthOneBased) {
        if (inflation == null) {
            return new CpiResolution(null, false);
        }
        Map<Integer, Map<Integer, BigDecimal>> byYear = inflation.monthlyByYear();
        if (byYear != null) {
            Map<Integer, BigDecimal> yearMap = byYear.get(year);
            if (yearMap != null && yearMap.get(monthOneBased) != null) {
                return new CpiResolution(yearMap.get(monthOneBased), false);
            }
            for (int y = year; y >= year - 3; y--) {
                Map<Integer, BigDecimal> ym = byYear.get(y);
                if (ym == null || ym.isEmpty()) {
                    continue;
                }
                int maxMonth = (y == year) ? monthOneBased : 12;
                for (int m = maxMonth; m >= 1; m--) {
                    BigDecimal candidate = ym.get(m);
                    if (candidate != null) {
                        return new CpiResolution(candidate, true);
                    }
                }
            }
        }
        if (inflation.currentCpi() != null) {
            return new CpiResolution(inflation.currentCpi(), true);
        }
        return new CpiResolution(null, false);
    }
}
