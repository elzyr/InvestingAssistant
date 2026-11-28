package pl.investassist.portfolio.valuation;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import pl.investassist.common.enums.AssetCategory;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.port.MarketDataPort;

final class PortfolioStepValuator {

  private PortfolioStepValuator() {}

  record StepGroupContributor(
      String key,
      String name,
      String symbol,
      String category,
      BigDecimal valuationBase,
      BigDecimal costBase,
      BigDecimal grossProfit,
      BigDecimal netProfit) {}

  record StepCategoryTotals(
      String categoryCode,
      BigDecimal invested,
      BigDecimal currentValue,
      BigDecimal profitGross,
      BigDecimal profitNet) {}

  record StepValuationResult(
      BigDecimal grossTotalValue,
      BigDecimal totalInvested,
      BigDecimal equityValue,
      BigDecimal bondValue,
      BigDecimal totalDividends,
      BigDecimal totalRealizedProfit,
      BigDecimal grossProfit,
      BigDecimal totalBelkaTax,
      BigDecimal netProfit,
      BigDecimal displayValuation,
      BigDecimal settledTax,
      Map<String, StepGroupContributor> contributorsByKey,
      Map<String, StepCategoryTotals> categoriesByCode,
      boolean degraded,
      BigDecimal completedBondCouponsBase,
      List<MetricsCalculator.CashFlow> completedBondCouponFlows) {

    StepValuationResult(
        BigDecimal grossTotalValue,
        BigDecimal totalInvested,
        BigDecimal equityValue,
        BigDecimal bondValue,
        BigDecimal totalDividends,
        BigDecimal totalRealizedProfit,
        BigDecimal grossProfit,
        BigDecimal totalBelkaTax,
        BigDecimal netProfit,
        BigDecimal displayValuation,
        BigDecimal settledTax,
        Map<String, StepGroupContributor> contributorsByKey,
        Map<String, StepCategoryTotals> categoriesByCode) {
      this(
          grossTotalValue, totalInvested, equityValue, bondValue, totalDividends,
          totalRealizedProfit, grossProfit, totalBelkaTax, netProfit, displayValuation,
          settledTax, contributorsByKey, categoriesByCode, false, BigDecimal.ZERO, List.of());
    }
  }

  private static final class GroupAccumulator {
    final String groupKey;
    final String name;
    final String symbol;
    final String categoryCode;
    final boolean isBond;
    final Set<UUID> instrumentIds = new LinkedHashSet<>();
    BigDecimal grossValueBase = BigDecimal.ZERO;
    BigDecimal costBase = BigDecimal.ZERO;
    BigDecimal realizedBase = BigDecimal.ZERO;
    BigDecimal dividendsBase = BigDecimal.ZERO;
    BigDecimal dividendTaxBase = BigDecimal.ZERO;

    GroupAccumulator(
        String groupKey,
        String name,
        String symbol,
        String categoryCode,
        boolean isBond) {
      this.groupKey = groupKey;
      this.name = name;
      this.symbol = symbol;
      this.categoryCode = categoryCode;
      this.isBond = isBond;
    }
  }

  private static final class CategoryAccumulator {
    BigDecimal invested = BigDecimal.ZERO;
    BigDecimal currentValue = BigDecimal.ZERO;
    BigDecimal profitGross = BigDecimal.ZERO;
    BigDecimal profitNet = BigDecimal.ZERO;
  }

  static StepValuationResult evaluate(
      PortfolioLedger.IncrementalLedger ledger,
      ValuationContext ctx,
      boolean isToday,
      boolean includeContributors,
      boolean includeCategories) {

    LocalDate stepDate = ctx.today();
    LocalDateTime stepDateTime = stepDate.atTime(23, 59, 59);
    Map<String, GroupAccumulator> groups = new LinkedHashMap<>();
    BigDecimal equityGross = BigDecimal.ZERO;
    BigDecimal bondGross = BigDecimal.ZERO;
    BigDecimal completedBondCouponsBase = BigDecimal.ZERO;
    BigDecimal openBondGain = BigDecimal.ZERO;
    List<MetricsCalculator.CashFlow> completedBondCouponFlows = new ArrayList<>();
    List<TaxPolicy.Art30aPayoutEvent> completedBondArt30aEvents = new ArrayList<>();
    boolean degraded = false;

    for (Map.Entry<PortfolioLedger.PositionKey, PortfolioLedger.PositionAccumulator> entry :
        ledger.accumulators().entrySet()) {
      PortfolioLedger.PositionKey key = entry.getKey();
      PortfolioLedger.PositionAccumulator acc = entry.getValue();
      UUID instId = key.instrumentId();
      Instrument inst = ctx.instruments().get(instId);

      boolean isBond = BondValuationEngine.isBondPosition(inst, key.seriesName(), acc.hasBondMetadata());
      String instSymbol = inst != null && inst.getSymbol() != null ? inst.getSymbol() : instId.toString();
      String quoteKey = ValuationSupport.normalizeSymbolKey(inst, instId);
      String instName = inst != null && inst.getName() != null ? inst.getName() : instSymbol;

      String groupKey;
      String groupSymbol;
      String groupName;
      if (isBond) {
        String family = BondValuationEngine.resolveBondFamily(
            key.seriesName() != null ? key.seriesName() : instSymbol,
            acc.lastTermMonths());
        groupKey = "BOND:" + family;
        groupSymbol = family;
        groupName = family;
      } else {
        groupKey = "INST:" + instId;
        groupSymbol = instSymbol;
        groupName = instName;
      }

      String categoryCode = ValuationSupport.resolveCategoryCode(
          inst, key.seriesName(), acc.hasBondMetadata());
      GroupAccumulator group = groups.computeIfAbsent(
          groupKey,
          ignored -> new GroupAccumulator(groupKey, groupName, groupSymbol, categoryCode, isBond));
      group.instrumentIds.add(instId);

      BigDecimal qty = acc.currentQuantity();
      BigDecimal costBase = acc.currentCostBase();
      BigDecimal realizedBase = acc.realizedProfitBase();
      BigDecimal grossValBase = BigDecimal.ZERO;

      if (isBond) {
        ValuationSupport.BondLotsStepResult bondStep = ValuationSupport.evaluateBondLotsNative(
            acc.allLots(), stepDateTime, ctx.monthlyCpiByYear(), ctx.nbpReferenceRates(),
            key.seriesName() != null ? key.seriesName() : instSymbol);
        boolean needFx = qty.compareTo(BigDecimal.ZERO) > 0
            || bondStep.completedCouponsNative().compareTo(BigDecimal.ZERO) > 0;
        if (needFx) {
          String qCur = ValuationSupport.resolveQuoteCurrency(inst, null, null);
          BigDecimal fx = ValuationSupport.requireFxRate(
              qCur, ctx.baseCurrency(), ctx.fxSeriesByPair(), stepDate, isToday);
          if (qty.compareTo(BigDecimal.ZERO) > 0) {
            grossValBase = bondStep.carryingValueNative().multiply(fx);
            bondGross = bondGross.add(grossValBase);
            openBondGain = openBondGain.add(grossValBase.subtract(costBase));
            degraded |= bondStep.rateUnavailable();
          }
          BigDecimal couponsBase = bondStep.completedCouponsNative().multiply(fx);
          if (couponsBase.compareTo(BigDecimal.ZERO) > 0) {
            group.dividendsBase = group.dividendsBase.add(couponsBase);
            group.dividendTaxBase = group.dividendTaxBase.add(TaxPolicy.computeBelka(couponsBase, false));
            completedBondCouponsBase = completedBondCouponsBase.add(couponsBase);
            completedBondArt30aEvents.add(
                new TaxPolicy.Art30aPayoutEvent(stepDateTime, couponsBase, BigDecimal.ZERO));
            for (MetricsCalculator.CashFlow flow : bondStep.couponFlowsNative()) {
              BigDecimal couponFx = ValuationSupport.requireFxRate(
                  qCur, ctx.baseCurrency(), ctx.fxSeriesByPair(), flow.date().toLocalDate(), false);
              completedBondCouponFlows.add(
                  new MetricsCalculator.CashFlow(flow.amount() * couponFx.doubleValue(), flow.date()));
            }
          }
        }
      } else if (qty.compareTo(BigDecimal.ZERO) > 0) {
        MarketDataPort.MarketQuote quote = isToday && ctx.quotes() != null ? ctx.quotes().get(quoteKey) : null;
        MarketDataPort.PriceHistory history = ctx.histories() != null ? ctx.histories().get(quoteKey) : null;
        if (isToday && (quote == null || quote.unavailable() || quote.price() == null)) {
          degraded = true;
        }
        String qCur = ValuationSupport.resolveQuoteCurrency(inst, quote, history);
        BigDecimal fx = ValuationSupport.requireFxRate(qCur, ctx.baseCurrency(), ctx.fxSeriesByPair(), stepDate, isToday);
        BigDecimal unitPrice = ValuationSupport.resolveEquityUnitPrice(
            quote, history, stepDate, isToday, acc.currentAvgBuyPriceNative());
        grossValBase = qty.multiply(unitPrice).multiply(fx);
        equityGross = equityGross.add(grossValBase);
      }

      group.grossValueBase = group.grossValueBase.add(grossValBase);
      group.costBase = group.costBase.add(costBase);
      group.realizedBase = group.realizedBase.add(realizedBase);
    }

    Map<PortfolioLedger.DividendKey, BigDecimal> remainingDivs =
        new LinkedHashMap<>(ledger.dividendsByKey());
    Map<PortfolioLedger.DividendKey, BigDecimal> remainingDivTax =
        new LinkedHashMap<>(ledger.dividendTaxByKey());
    for (GroupAccumulator group : groups.values()) {
      for (UUID instId : group.instrumentIds) {
        PortfolioLedger.DividendKey scopedKey =
            new PortfolioLedger.DividendKey(instId, group.isBond ? group.symbol : null);
        BigDecimal div = remainingDivs.remove(scopedKey);
        if (div != null) {
          group.dividendsBase = group.dividendsBase.add(div);
        }
        BigDecimal divTax = remainingDivTax.remove(scopedKey);
        if (divTax != null) {
          group.dividendTaxBase = group.dividendTaxBase.add(divTax);
        }
      }
    }

    Set<UUID> bondGroupInstIds = new LinkedHashSet<>();
    for (GroupAccumulator group : groups.values()) {
      if (group.isBond) {
        bondGroupInstIds.addAll(group.instrumentIds);
      }
    }
    for (UUID instId : bondGroupInstIds) {
      PortfolioLedger.DividendKey unscopedKey = new PortfolioLedger.DividendKey(instId, null);
      BigDecimal unscoped = remainingDivs.remove(unscopedKey);
      BigDecimal unscopedTax = remainingDivTax.remove(unscopedKey);
      if (unscoped == null || unscoped.compareTo(BigDecimal.ZERO) == 0) {
        continue;
      }
      List<GroupAccumulator> sharing = new ArrayList<>();
      for (GroupAccumulator group : groups.values()) {
        if (group.isBond && group.instrumentIds.contains(instId)) {
          sharing.add(group);
        }
      }
      if (!sharing.isEmpty()) {
        double[] shares = ValuationSupport.resolveShares(
            sharing,
            g -> g.grossValueBase.doubleValue(),
            g -> g.costBase.doubleValue());
        List<BigDecimal> pieces = ValuationSupport.splitAmount(unscoped, shares);
        List<BigDecimal> taxPieces = ValuationSupport.splitAmount(unscopedTax, shares);
        for (int i = 0; i < sharing.size(); i++) {
          sharing.get(i).dividendsBase = sharing.get(i).dividendsBase.add(pieces.get(i));
          sharing.get(i).dividendTaxBase = sharing.get(i).dividendTaxBase.add(taxPieces.get(i));
        }
      }
    }

    for (Map.Entry<PortfolioLedger.DividendKey, BigDecimal> orphan : remainingDivs.entrySet()) {
      BigDecimal div = orphan.getValue();
      if (div == null || div.compareTo(BigDecimal.ZERO) == 0) {
        continue;
      }
      PortfolioLedger.DividendKey divKey = orphan.getKey();
      UUID instId = divKey.instrumentId();
      String family = divKey.bondFamily();
      boolean orphanIsBond = family != null;
      Instrument inst = ctx.instruments().get(instId);
      String sym = orphanIsBond
          ? family
          : (inst != null && inst.getSymbol() != null ? inst.getSymbol() : instId.toString());
      String name = orphanIsBond
          ? family
          : (inst != null && inst.getName() != null ? inst.getName() : sym);
      String groupKey = orphanIsBond ? "BOND:" + family : "INST:" + instId;
      String cat = orphanIsBond
          ? AssetCategory.TREASURY_BONDS.name()
          : ValuationSupport.resolveCategoryCode(inst, null, false);
      GroupAccumulator orphanGroup = groups.computeIfAbsent(
          groupKey, ignored -> new GroupAccumulator(groupKey, name, sym, cat, orphanIsBond));
      orphanGroup.dividendsBase = orphanGroup.dividendsBase.add(div);
      BigDecimal orphanTax = remainingDivTax.remove(divKey);
      if (orphanTax != null) {
        orphanGroup.dividendTaxBase = orphanGroup.dividendTaxBase.add(orphanTax);
      }
    }

    BigDecimal grossTotalValue = BigDecimal.ZERO;
    BigDecimal totalInvested = BigDecimal.ZERO;
    BigDecimal totalRealized = BigDecimal.ZERO;

    Map<String, StepGroupContributor> contributors = includeContributors ? new LinkedHashMap<>() : Map.of();
    Map<String, CategoryAccumulator> categories = includeCategories ? new LinkedHashMap<>() : null;

    for (GroupAccumulator group : groups.values()) {
      TaxPolicy.PositionTaxBreakdown groupTax = TaxPolicy.computePositionTax(
          group.grossValueBase, group.costBase, group.realizedBase,
          group.dividendsBase, group.dividendTaxBase, !ctx.shouldApplyTax());
      BigDecimal groupDisplayValue = ctx.shouldApplyTax()
          ? groupTax.netValue()
          : group.grossValueBase;

      grossTotalValue = grossTotalValue.add(group.grossValueBase);
      totalInvested = totalInvested.add(group.costBase);
      totalRealized = totalRealized.add(group.realizedBase);

      if (includeContributors) {
        contributors.put(
            group.groupKey,
            new StepGroupContributor(
                group.groupKey,
                group.name,
                group.symbol,
                group.categoryCode,
                groupDisplayValue,
                group.costBase,
                groupTax.grossProfit(),
                groupTax.netProfit()));
      }

      if (categories != null) {
        CategoryAccumulator catBucket = categories.computeIfAbsent(
            group.categoryCode, ignored -> new CategoryAccumulator());
        catBucket.invested = catBucket.invested.add(group.costBase);
        catBucket.currentValue = catBucket.currentValue.add(groupDisplayValue);
        catBucket.profitGross = catBucket.profitGross.add(groupTax.grossProfit());
        catBucket.profitNet = catBucket.profitNet.add(groupTax.netProfit());
      }
    }

    BigDecimal totalDividends = ledger.totalDividends().add(completedBondCouponsBase);
    BigDecimal grossProfit = grossTotalValue
        .subtract(totalInvested)
        .add(totalDividends)
        .add(totalRealized);
    List<TaxPolicy.Art30aPayoutEvent> allArt30aEvents = new ArrayList<>(ledger.art30aEvents());
    allArt30aEvents.addAll(completedBondArt30aEvents);
    TaxPolicy.PortfolioTaxResult portfolioTax = TaxPolicy.computePortfolioTax(
        ledger.disposalEvents(),
        allArt30aEvents,
        grossTotalValue,
        totalInvested,
        openBondGain,
        stepDate.getYear(),
        !ctx.shouldApplyTax());
    BigDecimal totalBelka = portfolioTax.totalTax();
    BigDecimal netProfit = grossProfit.subtract(totalBelka);
    BigDecimal displayValuation = ctx.shouldApplyTax()
        ? grossTotalValue.subtract(portfolioTax.hypotheticalOpenTax())
        : grossTotalValue;
    BigDecimal settledTax = portfolioTax.settledTax();

    Map<String, StepCategoryTotals> finalizedCategories = Map.of();
    if (categories != null && !categories.isEmpty()) {
      Map<String, StepCategoryTotals> out = new LinkedHashMap<>(categories.size());
      for (Map.Entry<String, CategoryAccumulator> cEntry : categories.entrySet()) {
        CategoryAccumulator b = cEntry.getValue();
        out.put(
            cEntry.getKey(),
            new StepCategoryTotals(
                cEntry.getKey(), b.invested, b.currentValue, b.profitGross, b.profitNet));
      }
      finalizedCategories = out;
    }

    return new StepValuationResult(
        grossTotalValue,
        totalInvested,
        equityGross,
        bondGross,
        totalDividends,
        totalRealized,
        grossProfit,
        totalBelka,
        netProfit,
        displayValuation,
        settledTax,
        contributors,
        finalizedCategories,
        degraded,
        completedBondCouponsBase,
        completedBondCouponFlows);
  }
}
