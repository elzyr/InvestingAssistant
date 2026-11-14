package pl.investassist.portfolio.valuation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import pl.investassist.common.enums.AssetCategory;
import pl.investassist.common.enums.InstrumentType;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationPositionDto;
import pl.investassist.portfolio.dto.response.PortfolioValuationResponse.ValuationSubPositionDto;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class BondPositionBuilder {

    private static final Logger log = LoggerFactory.getLogger(BondPositionBuilder.class);

    private BondPositionBuilder() {
    }

    record BondCouponBase(LocalDate date, BigDecimal grossBase) {}

    record SubGroupKey(String seriesName, LocalDate purchaseDate) {}

    record BondLotEntry(
            PortfolioLedger.FifoLot lot,
            UUID instrumentId,
            BigDecimal valueBase,
            BigDecimal costBase,
            BigDecimal annualYieldPct,
            boolean rateUnavailable,
            List<BondCouponBase> completedCoupons
    ) {
    }

    private static String collectSeriesMetadata(
            ValuationEngine.Accumulator acc,
            PortfolioLedger.PositionKey key,
            PortfolioLedger.PositionReplayResult posResult,
            Instrument inst) {
        String family = resolveFamily(key, posResult, inst);
        List<UUID> ids = acc.bondInstrumentIdsByFamily.computeIfAbsent(family, ignored -> new ArrayList<>());
        if (!ids.contains(key.instrumentId())) {
            ids.add(key.instrumentId());
        }
        if (posResult.realizedProfitBase().compareTo(BigDecimal.ZERO) != 0) {
            acc.closedBondRealizedByFamily.merge(family, posResult.realizedProfitBase(), BigDecimal::add);
        }
        if (!posResult.cashFlows().isEmpty()) {
            acc.bondFlowsByFamily.computeIfAbsent(family, ignored -> new ArrayList<>())
                    .addAll(posResult.cashFlows());
        }
        return family;
    }

    static void collectClosedBondPosition(
            ValuationEngine.Accumulator acc,
            PortfolioLedger.PositionKey key,
            PortfolioLedger.PositionReplayResult posResult,
            Instrument inst) {
        collectSeriesMetadata(acc, key, posResult, inst);
    }

    static void collectBondLots(
            ValuationEngine.Accumulator acc,
            PortfolioLedger.PositionKey key,
            PortfolioLedger.PositionReplayResult posResult,
            Instrument inst,
            ValuationContext ctx) {
        String instSymbol = inst != null ? inst.getSymbol() : "";
        String family = collectSeriesMetadata(acc, key, posResult, inst);
        if (posResult.activeLots().isEmpty()) {
            return;
        }

        String quoteCurrency = ValuationSupport.resolveQuoteCurrency(inst, null, null);
        BigDecimal fxRate = ValuationSupport.requireFxRate(quoteCurrency, ctx.baseCurrency(), ctx.fxSeriesByPair(), ctx.today(), true);

        for (PortfolioLedger.FifoLot lot : posResult.activeLots()) {
            String effectiveSeries = lot.seriesName() != null ? lot.seriesName() : instSymbol;
            BondValuationEngine.LotValuation lotVal = BondValuationEngine.evaluateLot(
                    lot.executedAt(),
                    lot.bondRate(),
                    lot.bondMargin(),
                    ctx.monthlyCpiByYear(),
                    ctx.nbpReferenceRates(),
                    ctx.effectiveNow(),
                    effectiveSeries,
                    lot.termMonths());
            BigDecimal lotValueBase = lot.remainingQuantity()
                    .multiply(BigDecimal.valueOf(lotVal.unitPriceNative()))
                    .multiply(fxRate);
            BigDecimal lotCostBase = lot.remainingCostBase();
            BigDecimal lotYieldPct = lotVal.rateUnavailable()
                    ? null
                    : BigDecimal.valueOf(lotVal.currentAnnualRatePct());

            List<BondCouponBase> lotCoupons = new ArrayList<>(lotVal.completedCoupons().size());
            for (BondValuationEngine.CompletedCoupon c : lotVal.completedCoupons()) {
                BigDecimal cFx = ValuationSupport.requireFxRate(
                        quoteCurrency, ctx.baseCurrency(), ctx.fxSeriesByPair(), c.couponDate(), false);
                BigDecimal grossBase = BigDecimal.valueOf(c.grossCouponNative())
                        .multiply(lot.remainingQuantity())
                        .multiply(cFx);
                lotCoupons.add(new BondCouponBase(c.couponDate(), grossBase));
                acc.completedBondCouponsBase = acc.completedBondCouponsBase.add(grossBase);
                acc.completedBondArt30aEvents.add(new TaxPolicy.Art30aPayoutEvent(
                        c.couponDate().atStartOfDay(), grossBase, BigDecimal.ZERO));
                BigDecimal couponNet = grossBase.subtract(TaxPolicy.computeBelka(grossBase, !ctx.shouldApplyTax()));
                acc.completedBondCouponFlows.add(new MetricsCalculator.CashFlow(
                        couponNet.doubleValue(), c.couponDate().atStartOfDay()));
            }

            acc.bondLotsByFamily.computeIfAbsent(family, ignored -> new ArrayList<>())
                    .add(new BondLotEntry(
                            lot, key.instrumentId(), lotValueBase, lotCostBase, lotYieldPct,
                            lotVal.rateUnavailable(), lotCoupons));

            acc.grossTotalValue = acc.grossTotalValue.add(lotValueBase);
            acc.totalBondValue = acc.totalBondValue.add(lotValueBase);
        }
    }

    private static final class FamilyAccumulator {
        final String family;
        final List<UUID> openInstIds = new ArrayList<>();
        final List<UUID> familyInstIds;
        final Map<SubGroupKey, List<BondLotEntry>> entriesBySubGroup = new LinkedHashMap<>();
        final List<MetricsCalculator.CashFlow> familyFlows = new ArrayList<>();
        BigDecimal sumQty = BigDecimal.ZERO;
        BigDecimal sumVal = BigDecimal.ZERO;
        BigDecimal sumCost = BigDecimal.ZERO;
        BigDecimal minRate;
        BigDecimal maxRate;
        BigDecimal latestMargin;
        BigDecimal familyDivs = BigDecimal.ZERO;
        boolean rateUnavailable;

        FamilyAccumulator(String family, List<UUID> familyInstIds) {
            this.family = family;
            this.familyInstIds = familyInstIds;
        }
    }

    static void buildBondPositions(
            ValuationEngine.Accumulator acc,
            Map<PortfolioLedger.DividendKey, List<MetricsCalculator.CashFlow>> dividendFlowsByKey,
            Map<PortfolioLedger.DividendKey, BigDecimal> remainingDividends,
            ValuationContext ctx) {
        List<FamilyAccumulator> openFamilies = new ArrayList<>(acc.bondLotsByFamily.size());
        Set<UUID> openBondInstIds = new LinkedHashSet<>();

        for (Map.Entry<String, List<BondLotEntry>> familyEntry : acc.bondLotsByFamily.entrySet()) {
            String family = familyEntry.getKey();
            List<UUID> familyInstIds = acc.bondInstrumentIdsByFamily.getOrDefault(family, List.of());
            FamilyAccumulator state = new FamilyAccumulator(family, familyInstIds);

            for (BondLotEntry e : familyEntry.getValue()) {
                state.rateUnavailable |= e.rateUnavailable();
                state.sumQty = state.sumQty.add(e.lot().remainingQuantity());
                state.sumVal = state.sumVal.add(e.valueBase());
                state.sumCost = state.sumCost.add(e.costBase());
                if (e.annualYieldPct() != null) {
                    acc.weightedBondYieldNumerator = acc.weightedBondYieldNumerator
                            .add(e.valueBase().multiply(e.annualYieldPct()));
                    acc.weightedBondYieldDenominator = acc.weightedBondYieldDenominator.add(e.valueBase());
                }

                if (!state.openInstIds.contains(e.instrumentId())) {
                    state.openInstIds.add(e.instrumentId());
                }
                if (e.lot().bondRate() != null) {
                    state.minRate = state.minRate == null ? e.lot().bondRate() : state.minRate.min(e.lot().bondRate());
                    state.maxRate = state.maxRate == null ? e.lot().bondRate() : state.maxRate.max(e.lot().bondRate());
                }
                if (e.lot().bondMargin() != null) {
                    state.latestMargin = e.lot().bondMargin();
                }
                String lotSeries = e.lot().seriesName() != null && !e.lot().seriesName().isBlank()
                        ? e.lot().seriesName()
                        : family;
                LocalDate lotDate = e.lot().executedAt() != null
                        ? e.lot().executedAt().toLocalDate()
                        : ctx.today();
                state.entriesBySubGroup
                        .computeIfAbsent(new SubGroupKey(lotSeries, lotDate), ignored -> new ArrayList<>())
                        .add(e);
                for (BondCouponBase c : e.completedCoupons()) {
                    state.familyDivs = state.familyDivs.add(c.grossBase());
                    BigDecimal cNet = c.grossBase().subtract(
                            TaxPolicy.computeBelka(c.grossBase(), !ctx.shouldApplyTax()));
                    state.familyFlows.add(new MetricsCalculator.CashFlow(
                            cNet.doubleValue(), c.date().atStartOfDay()));
                }
            }

            List<UUID> effectiveInstIds = familyInstIds.isEmpty() ? state.openInstIds : familyInstIds;
            openBondInstIds.addAll(effectiveInstIds);

            for (UUID famInstId : effectiveInstIds) {
                PortfolioLedger.DividendKey scopedKey = new PortfolioLedger.DividendKey(famInstId, family);
                BigDecimal div = remainingDividends.remove(scopedKey);
                if (div != null) {
                    state.familyDivs = state.familyDivs.add(div);
                }
                List<MetricsCalculator.CashFlow> divFlows = dividendFlowsByKey.remove(scopedKey);
                if (divFlows != null) {
                    state.familyFlows.addAll(divFlows);
                }
            }
            List<MetricsCalculator.CashFlow> recordedFlows = acc.bondFlowsByFamily.get(family);
            if (recordedFlows != null) {
                state.familyFlows.addAll(recordedFlows);
            }
            openFamilies.add(state);
        }

        for (UUID instId : openBondInstIds) {
            PortfolioLedger.DividendKey unscopedKey = new PortfolioLedger.DividendKey(instId, null);
            BigDecimal unscopedDiv = remainingDividends.remove(unscopedKey);
            List<MetricsCalculator.CashFlow> unscopedFlows = dividendFlowsByKey.remove(unscopedKey);
            if (unscopedDiv == null && (unscopedFlows == null || unscopedFlows.isEmpty())) {
                continue;
            }
            List<FamilyAccumulator> sharing = openFamilies.stream()
                    .filter(s -> s.familyInstIds.contains(instId) || s.openInstIds.contains(instId))
                    .toList();
            if (sharing.isEmpty()) {
                continue;
            }
            double[] shares = ValuationSupport.resolveShares(
                    sharing,
                    s -> s.sumVal.doubleValue(),
                    s -> s.sumCost.doubleValue());
            List<BigDecimal> divPieces = ValuationSupport.splitAmount(unscopedDiv, shares);
            for (int i = 0; i < sharing.size(); i++) {
                FamilyAccumulator target = sharing.get(i);
                target.familyDivs = target.familyDivs.add(divPieces.get(i));
                if (unscopedFlows != null) {
                    double ratio = shares[i];
                    for (MetricsCalculator.CashFlow cf : unscopedFlows) {
                        target.familyFlows.add(new MetricsCalculator.CashFlow(cf.amount() * ratio, cf.date()));
                    }
                }
            }
        }

        for (FamilyAccumulator state : openFamilies) {
            String family = state.family;
            BigDecimal familyRealized = acc.closedBondRealizedByFamily.getOrDefault(family, BigDecimal.ZERO);
            TaxPolicy.PositionTaxBreakdown tax = TaxPolicy.computePositionTax(
                    state.sumVal, state.sumCost, familyRealized, state.familyDivs, !ctx.shouldApplyTax());
            BigDecimal avgPrice = state.sumQty.compareTo(BigDecimal.ZERO) > 0
                    ? state.sumVal.divide(state.sumQty, 4, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(100);
            BigDecimal avgBuyPrice = state.sumQty.compareTo(BigDecimal.ZERO) > 0
                    ? state.sumCost.divide(state.sumQty, 4, RoundingMode.HALF_UP)
                    : BigDecimal.valueOf(100);

            List<ValuationSubPositionDto> subs = new ArrayList<>();
            List<SubGroupKey> sortedSubKeys = state.entriesBySubGroup.keySet().stream()
                    .sorted(Comparator.comparing(SubGroupKey::purchaseDate).reversed()
                            .thenComparing(SubGroupKey::seriesName))
                    .toList();
            for (SubGroupKey subKey : sortedSubKeys) {
                subs.add(buildBondSubPosition(family, subKey, state.entriesBySubGroup.get(subKey), ctx));
            }

            LocalDateTime realizedTaxDate = state.familyFlows.stream()
                    .filter(cf -> cf.amount() > 0)
                    .map(MetricsCalculator.CashFlow::date)
                    .max(LocalDateTime::compareTo)
                    .orElse(ctx.effectiveNow());
            List<MetricsCalculator.CashFlow> xirrFlows = ValuationSupport.withSettledTaxFlow(
                    state.familyFlows, tax.settledTax(), realizedTaxDate);
            MetricsCalculator.XirrResult famXirr = MetricsCalculator.calculateHoldingXirr(
                    xirrFlows, tax.netValue().doubleValue(), ctx.effectiveNow(), ValuationSupport.XIRR_MIN_DAYS);
            UUID primaryInstId = state.openInstIds.isEmpty() ? null : state.openInstIds.getFirst();

            if (state.rateUnavailable) {
                log.warn("Missing rate/CPI/NBP reference rate for bond family={}; valuation degraded", family);
            }

            acc.totalInvested = acc.totalInvested.add(state.sumCost);
            acc.totalRealizedProfit = acc.totalRealizedProfit.add(familyRealized);

            acc.rawPositions.add(new ValuationPositionDto(
                    "bond-" + family,
                    primaryInstId,
                    family,
                    family,
                    InstrumentType.BOND.name(),
                    AssetCategory.TREASURY_BONDS.name(),
                    ctx.baseCurrency(),
                    ValuationSupport.scale(state.sumQty, 4),
                    ValuationSupport.scale(avgPrice, 4),
                    ValuationSupport.scale(avgBuyPrice, 4),
                    ValuationSupport.scale(state.sumVal, 2),
                    ValuationSupport.scale(state.sumCost, 2),
                    ValuationSupport.scale(tax.netProfit(), 2),
                    ValuationSupport.scale(tax.grossProfit(), 2),
                    ValuationSupport.pct(tax.netProfit(), state.sumCost),
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    BigDecimal.ZERO,
                    ValuationSupport.toXirrPct(famXirr),
                    ValuationSupport.scale(state.familyDivs, 2),
                    ValuationSupport.scale(familyRealized, 2),
                    family,
                    state.maxRate,
                    state.minRate,
                    state.maxRate,
                    state.latestMargin,
                    state.rateUnavailable,
                    state.rateUnavailable,
                    "BONDS_YEARLY",
                    null,
                    subs));
        }

        for (Map.Entry<String, List<UUID>> entry : acc.bondInstrumentIdsByFamily.entrySet()) {
            String family = entry.getKey();
            if (acc.bondLotsByFamily.containsKey(family)) {
                continue;
            }
            BigDecimal closedRealized = acc.closedBondRealizedByFamily.getOrDefault(family, BigDecimal.ZERO);
            BigDecimal closedDivs = BigDecimal.ZERO;
            for (UUID instId : entry.getValue()) {
                BigDecimal scoped = remainingDividends.remove(new PortfolioLedger.DividendKey(instId, family));
                if (scoped != null) {
                    closedDivs = closedDivs.add(scoped);
                }
                BigDecimal unscoped = remainingDividends.remove(new PortfolioLedger.DividendKey(instId, null));
                if (unscoped != null) {
                    closedDivs = closedDivs.add(unscoped);
                }
            }
            acc.totalRealizedProfit = acc.totalRealizedProfit.add(closedRealized);
        }
    }

    private static ValuationSubPositionDto buildBondSubPosition(
            String family,
            SubGroupKey subKey,
            List<BondLotEntry> subEntries,
            ValuationContext ctx) {
        BigDecimal yQty = BigDecimal.ZERO;
        BigDecimal yVal = BigDecimal.ZERO;
        BigDecimal yCost = BigDecimal.ZERO;
        BigDecimal yCoupons = BigDecimal.ZERO;
        BigDecimal yRate = null;
        BigDecimal yMargin = null;
        List<MetricsCalculator.CashFlow> subFlows = new ArrayList<>();

        for (BondLotEntry ye : subEntries) {
            yQty = yQty.add(ye.lot().remainingQuantity());
            yVal = yVal.add(ye.valueBase());
            yCost = yCost.add(ye.costBase());
            if (ye.lot().bondRate() != null) yRate = ye.lot().bondRate();
            if (ye.lot().bondMargin() != null) yMargin = ye.lot().bondMargin();
            if (ye.lot().executedAt() != null) {
                subFlows.add(new MetricsCalculator.CashFlow(
                        -ye.costBase().doubleValue(), ye.lot().executedAt()));
            }
            for (BondCouponBase c : ye.completedCoupons()) {
                yCoupons = yCoupons.add(c.grossBase());
                BigDecimal cNet = c.grossBase().subtract(
                        TaxPolicy.computeBelka(c.grossBase(), !ctx.shouldApplyTax()));
                subFlows.add(new MetricsCalculator.CashFlow(
                        cNet.doubleValue(), c.date().atStartOfDay()));
            }
        }

        TaxPolicy.PositionTaxBreakdown tax = TaxPolicy.computePositionTax(
                yVal, yCost, BigDecimal.ZERO, yCoupons, !ctx.shouldApplyTax());
        BigDecimal yProfit = tax.netProfit();
        BigDecimal yNetVal = tax.netValue();
        BigDecimal yUnitPrice = yQty.compareTo(BigDecimal.ZERO) > 0
                ? yVal.divide(yQty, 4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(100);
        BigDecimal yAvgBuyPrice = yQty.compareTo(BigDecimal.ZERO) > 0
                ? yCost.divide(yQty, 4, RoundingMode.HALF_UP)
                : BigDecimal.valueOf(100);
        UUID singleTxId = subEntries.size() == 1 ? subEntries.getFirst().lot().txId() : null;
        MetricsCalculator.XirrResult yXirr = MetricsCalculator.calculateHoldingXirr(
                subFlows, yNetVal.doubleValue(), ctx.effectiveNow(), ValuationSupport.XIRR_MIN_DAYS);

        return new ValuationSubPositionDto(
                family + "-" + subKey.seriesName() + "-" + subKey.purchaseDate(),
                singleTxId,
                subKey.seriesName(),
                subKey.purchaseDate(),
                ValuationSupport.scale(yQty, 4),
                ValuationSupport.scale(yAvgBuyPrice, 4),
                ctx.baseCurrency(),
                BigDecimal.ZERO,
                ValuationSupport.scale(yCost, 2),
                ValuationSupport.scale(yVal, 2),
                ValuationSupport.scale(yProfit, 2),
                ValuationSupport.pct(yProfit, yCost),
                ValuationSupport.toXirrPct(yXirr),
                yUnitPrice,
                ValuationSupport.scale(yAvgBuyPrice, 4),
                yRate,
                yMargin,
                null);
    }

    private static String resolveFamily(
            PortfolioLedger.PositionKey key,
            PortfolioLedger.PositionReplayResult posResult,
            Instrument inst) {
        String instSymbol = inst != null ? inst.getSymbol() : "";
        String series = key.seriesName() != null && !key.seriesName().isBlank() ? key.seriesName() : instSymbol;
        String resolved = BondValuationEngine.resolveBondFamily(
                series, posResult.latestTermMonths());
        return resolved != null ? resolved : (series != null && !series.isBlank() ? series : "UNKNOWN");
    }
}
