package pl.investassist.portfolio.valuation;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import pl.investassist.common.enums.Currency;
import pl.investassist.common.exception.InsufficientPositionException;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.enums.TransactionType;

public final class PortfolioLedger {

  public static final Comparator<Transaction> TX_ORDER =
      Comparator.comparing(
              Transaction::getExecutedAt, Comparator.nullsFirst(Comparator.naturalOrder()))
          .thenComparingInt(
              t -> {
                if (t.getTransactionType() == TransactionType.BUY) return 0;
                if (t.getTransactionType() == TransactionType.DIVIDEND) return 2;
                return 1;
              })
          .thenComparing(Transaction::getId, Comparator.nullsLast(Comparator.naturalOrder()));

  private PortfolioLedger() {}

  public record PositionKey(UUID instrumentId, String seriesName) {}

  public record DividendKey(UUID instrumentId, String bondFamily) {}

  public static final class FifoLot {
    public record LotDeduction(LocalDateTime executedAt, BigDecimal quantity) {}

    private final UUID txId;
    private final BigDecimal initialQuantity;
    private BigDecimal remainingQuantity;
    private final BigDecimal initialCostNative;
    private final BigDecimal initialCostBase;
    private final BigDecimal resolvedNativeBuyPrice;
    private final BigDecimal txPrice;
    private final Currency txCurrency;
    private final LocalDateTime executedAt;
    private final BigDecimal bondRate;
    private final BigDecimal bondMargin;
    private final Integer termMonths;
    private final String seriesName;
    private final List<LotDeduction> deductions = new ArrayList<>();

    FifoLot(
        UUID txId,
        BigDecimal quantity,
        BigDecimal initialCostNative,
        BigDecimal initialCostBase,
        BigDecimal resolvedNativeBuyPrice,
        BigDecimal txPrice,
        Currency txCurrency,
        LocalDateTime executedAt,
        BigDecimal bondRate,
        BigDecimal bondMargin,
        Integer termMonths,
        String seriesName) {
      this.txId = txId;
      this.initialQuantity = quantity;
      this.remainingQuantity = quantity;
      this.initialCostNative = initialCostNative;
      this.initialCostBase = initialCostBase;
      this.resolvedNativeBuyPrice = resolvedNativeBuyPrice;
      this.txPrice = txPrice;
      this.txCurrency = txCurrency;
      this.executedAt = executedAt;
      this.bondRate = bondRate;
      this.bondMargin = bondMargin;
      this.termMonths = termMonths;
      this.seriesName = seriesName;
    }

    public UUID txId() { return txId; }
    public BigDecimal initialQuantity() { return initialQuantity; }
    public BigDecimal remainingQuantity() { return remainingQuantity; }
    public BigDecimal initialCostNative() { return initialCostNative; }
    public BigDecimal initialCostBase() { return initialCostBase; }
    public BigDecimal resolvedNativeBuyPrice() { return resolvedNativeBuyPrice; }
    public BigDecimal txPrice() { return txPrice; }
    public Currency txCurrency() { return txCurrency; }
    public LocalDateTime executedAt() { return executedAt; }
    public BigDecimal bondRate() { return bondRate; }
    public BigDecimal bondMargin() { return bondMargin; }
    public Integer termMonths() { return termMonths; }
    public String seriesName() { return seriesName; }

    public BigDecimal remainingCostNative() {
      if (initialQuantity.compareTo(BigDecimal.ZERO) <= 0) {
        return BigDecimal.ZERO;
      }
      return initialCostNative
          .multiply(remainingQuantity)
          .divide(initialQuantity, 10, RoundingMode.HALF_UP);
    }

    void recordDeduction(LocalDateTime soldAt, BigDecimal soldQty) {
      if (soldQty != null && soldQty.compareTo(BigDecimal.ZERO) > 0) {
        deductions.add(new LotDeduction(soldAt, soldQty));
      }
    }

    public BigDecimal quantityAt(LocalDate date) {
      if (date == null) {
        return remainingQuantity;
      }
      BigDecimal qty = initialQuantity;
      for (LotDeduction d : deductions) {
        if (d.executedAt() != null && d.executedAt().toLocalDate().isBefore(date)) {
          qty = qty.subtract(d.quantity());
        }
      }
      return qty.max(BigDecimal.ZERO);
    }

    public BigDecimal remainingCostBase() {
      if (initialQuantity.compareTo(BigDecimal.ZERO) <= 0) {
        return BigDecimal.ZERO;
      }
      return initialCostBase
          .multiply(remainingQuantity)
          .divide(initialQuantity, 10, RoundingMode.HALF_UP);
    }
  }

  public record PositionReplayResult(
      List<FifoLot> activeLots,
      BigDecimal totalQuantity,
      BigDecimal totalCostNative,
      BigDecimal totalCostBase,
      BigDecimal averageBuyPriceNative,
      BigDecimal realizedProfitBase,
      List<MetricsCalculator.CashFlow> cashFlows,
      BigDecimal latestBondMargin,
      Integer latestTermMonths,
      boolean hasBondMetadata,
      List<FifoLot> allLots) {

    public PositionReplayResult(
        List<FifoLot> activeLots,
        BigDecimal totalQuantity,
        BigDecimal totalCostNative,
        BigDecimal totalCostBase,
        BigDecimal averageBuyPriceNative,
        BigDecimal realizedProfitBase,
        List<MetricsCalculator.CashFlow> cashFlows,
        BigDecimal latestBondMargin,
        Integer latestTermMonths,
        boolean hasBondMetadata) {
      this(
          activeLots, totalQuantity, totalCostNative, totalCostBase, averageBuyPriceNative,
          realizedProfitBase, cashFlows, latestBondMargin, latestTermMonths, hasBondMetadata, activeLots);
    }
  }

  public static final class PositionAccumulator {
    private final List<FifoLot> lots = new ArrayList<>();
    private final List<MetricsCalculator.CashFlow> cashFlows = new ArrayList<>();
    private final List<TaxPolicy.CapitalDisposalEvent> disposalEvents = new ArrayList<>();
    private BigDecimal currentQty = BigDecimal.ZERO;
    private BigDecimal realizedProfitBase = BigDecimal.ZERO;
    private boolean hasBondMetadata;
    private BigDecimal lastBondMargin;
    private Integer lastTermMonths;

    public BigDecimal apply(Transaction tx, boolean strictSell) {
      return apply(tx, strictSell, false);
    }

    public BigDecimal apply(Transaction tx, boolean strictSell, boolean isBond) {
      if (BondValuationEngine.hasBondMetadata(tx)) {
        this.hasBondMetadata = true;
      }
      if (tx.getBondMargin() != null) {
        this.lastBondMargin = tx.getBondMargin();
      }
      if (tx.getTermMonths() != null) {
        this.lastTermMonths = tx.getTermMonths();
      }
      if (tx.getTransactionType() == TransactionType.BUY) {
        BigDecimal qty = nonNull(tx.getQuantity());
        BigDecimal resolvedPrice = nonNull(tx.getPrice());
        BigDecimal comm = nonNull(tx.getCommission());
        if (tx.getExchangeRate() != null
            && tx.getExchangeRate().compareTo(BigDecimal.ONE) != 0
            && tx.getExchangeRate().compareTo(BigDecimal.ZERO) > 0) {
          resolvedPrice = resolvedPrice.divide(tx.getExchangeRate(), 6, RoundingMode.HALF_UP);
          comm = comm.divide(tx.getExchangeRate(), 6, RoundingMode.HALF_UP);
        }
        BigDecimal costNative = resolvedPrice.multiply(qty).add(comm);
        BigDecimal costBase = resolveBuyCostBase(tx);

        lots.add(
            new FifoLot(
                tx.getId(),
                qty,
                costNative,
                costBase,
                resolvedPrice,
                tx.getPrice(),
                tx.getCurrency(),
                tx.getExecutedAt(),
                tx.getBondRate(),
                tx.getBondMargin(),
                tx.getTermMonths(),
                BondValuationEngine.normalizeSeries(tx.getSeriesName())));
        currentQty = currentQty.add(qty);
        if (tx.getExecutedAt() != null) {
          cashFlows.add(new MetricsCalculator.CashFlow(-costBase.doubleValue(), tx.getExecutedAt()));
        }
        return costBase;
      } else if (tx.getTransactionType() == TransactionType.SELL) {
        BigDecimal rawSellQty = nonNull(tx.getQuantity());
        BigDecimal sellQty = rawSellQty;
        if (currentQty.compareTo(rawSellQty) < 0) {
          if (strictSell) {
            BigDecimal diff = currentQty.subtract(rawSellQty);
            throw new InsufficientPositionException(
                "Insufficient quantity: transaction on "
                    + tx.getExecutedAt()
                    + " causes negative balance ("
                    + diff
                    + ")");
          }
          sellQty = currentQty;
        }
        if (sellQty.compareTo(BigDecimal.ZERO) <= 0) {
          return BigDecimal.ZERO;
        }

        BigDecimal proceedBase = resolveProceedsOrDividendBase(tx);
        if (rawSellQty.compareTo(BigDecimal.ZERO) > 0 && sellQty.compareTo(rawSellQty) < 0) {
          proceedBase = proceedBase.multiply(sellQty).divide(rawSellQty, 10, RoundingMode.HALF_UP);
        }
        BigDecimal matchedCostBase = BigDecimal.ZERO;
        BigDecimal toDeduct = sellQty;
        for (FifoLot lot : lots) {
          if (lot.remainingQuantity.compareTo(BigDecimal.ZERO) <= 0) {
            continue;
          }
          BigDecimal deduct = lot.remainingQuantity.min(toDeduct);
          BigDecimal lotCostPortion =
              lot.initialQuantity.compareTo(BigDecimal.ZERO) > 0
                  ? lot.initialCostBase
                      .multiply(deduct)
                      .divide(lot.initialQuantity, 10, RoundingMode.HALF_UP)
                  : BigDecimal.ZERO;
          matchedCostBase = matchedCostBase.add(lotCostPortion);
          lot.recordDeduction(tx.getExecutedAt(), deduct);
          lot.remainingQuantity = lot.remainingQuantity.subtract(deduct);
          toDeduct = toDeduct.subtract(deduct);
          if (toDeduct.compareTo(BigDecimal.ZERO) <= 0) {
            break;
          }
        }
        BigDecimal sellProfitBase = proceedBase.subtract(matchedCostBase);
        realizedProfitBase = realizedProfitBase.add(sellProfitBase);
        disposalEvents.add(new TaxPolicy.CapitalDisposalEvent(tx.getExecutedAt(), sellProfitBase, isBond));
        currentQty = currentQty.subtract(sellQty);
        if (tx.getExecutedAt() != null) {
          cashFlows.add(new MetricsCalculator.CashFlow(proceedBase.doubleValue(), tx.getExecutedAt()));
        }
        return proceedBase.negate();
      } else if (tx.getTransactionType() == TransactionType.DIVIDEND) {
        BigDecimal divBase = resolveProceedsOrDividendBase(tx);
        if (tx.getExecutedAt() != null) {
          cashFlows.add(new MetricsCalculator.CashFlow(divBase.doubleValue(), tx.getExecutedAt()));
        }
        return divBase.negate();
      }
      return BigDecimal.ZERO;
    }

    public List<FifoLot> activeLots() {
      return lots.stream().filter(l -> l.remainingQuantity.compareTo(BigDecimal.ZERO) > 0).toList();
    }

    public List<FifoLot> allLots() {
      return List.copyOf(lots);
    }

    public BigDecimal currentQuantity() {
      return currentQty;
    }

    public BigDecimal realizedProfitBase() {
      return realizedProfitBase;
    }

    public boolean hasBondMetadata() {
      return hasBondMetadata;
    }

    public BigDecimal lastBondMargin() {
      return lastBondMargin;
    }

    public Integer lastTermMonths() {
      return lastTermMonths;
    }

    public BigDecimal currentCostBase() {
      BigDecimal sum = BigDecimal.ZERO;
      for (FifoLot lot : lots) {
        if (lot.remainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
          sum = sum.add(lot.remainingCostBase());
        }
      }
      return sum;
    }

    public BigDecimal currentAvgBuyPriceNative() {
      if (currentQty.compareTo(BigDecimal.ZERO) <= 0) {
        return BigDecimal.ZERO;
      }
      BigDecimal totalCostNative = BigDecimal.ZERO;
      for (FifoLot lot : lots) {
        if (lot.remainingQuantity.compareTo(BigDecimal.ZERO) > 0) {
          totalCostNative = totalCostNative.add(lot.remainingCostNative());
        }
      }
      return totalCostNative.divide(currentQty, 6, RoundingMode.HALF_UP);
    }

    public PositionReplayResult toResult() {
      List<FifoLot> active = activeLots();
      BigDecimal totalQty = BigDecimal.ZERO;
      BigDecimal totalCostNative = BigDecimal.ZERO;
      BigDecimal totalCostBase = BigDecimal.ZERO;
      BigDecimal activeBondMargin = this.lastBondMargin;
      Integer activeTermMonths = this.lastTermMonths;
      for (FifoLot lot : active) {
        totalQty = totalQty.add(lot.remainingQuantity);
        totalCostNative = totalCostNative.add(lot.remainingCostNative());
        totalCostBase = totalCostBase.add(lot.remainingCostBase());
        if (lot.bondMargin != null) activeBondMargin = lot.bondMargin;
        if (lot.termMonths != null) activeTermMonths = lot.termMonths;
      }

      BigDecimal avgBuyPriceNative =
          totalQty.compareTo(BigDecimal.ZERO) > 0
              ? totalCostNative.divide(totalQty, 6, RoundingMode.HALF_UP)
              : BigDecimal.ZERO;

      return new PositionReplayResult(
          active,
          totalQty,
          totalCostNative,
          totalCostBase,
          avgBuyPriceNative,
          realizedProfitBase,
          List.copyOf(cashFlows),
          activeBondMargin,
          activeTermMonths,
          this.hasBondMetadata,
          List.copyOf(lots));
    }
  }

  public static final class IncrementalLedger {
    private final Map<UUID, Instrument> instruments;
    private final Map<PositionKey, PositionAccumulator> accumulators = new LinkedHashMap<>();
    private final Map<DividendKey, BigDecimal> dividendsByKey = new LinkedHashMap<>();
    private final Map<DividendKey, BigDecimal> dividendTaxByKey = new LinkedHashMap<>();
    private final List<MetricsCalculator.CashFlow> allFlows = new ArrayList<>();
    private final List<TaxPolicy.Art30aPayoutEvent> art30aEvents = new ArrayList<>();
    private BigDecimal totalDividends = BigDecimal.ZERO;
    private BigDecimal cumulativeExternalCashFlow = BigDecimal.ZERO;

    public IncrementalLedger(Map<UUID, Instrument> instruments) {
      this.instruments = instruments != null ? instruments : Map.of();
    }

    public void apply(Transaction tx) {
      Instrument inst = instruments.get(tx.getInstrumentId());
      boolean isBond = BondValuationEngine.isBondTransaction(tx, inst);
      if (tx.getTransactionType() == TransactionType.DIVIDEND) {
        BigDecimal divBase = resolveProceedsOrDividendBase(tx);
        BigDecimal taxBase = resolveDividendTaxBase(tx);
        DividendKey divKey = resolveDividendKey(tx, isBond);
        dividendsByKey.merge(divKey, divBase, BigDecimal::add);
        dividendTaxByKey.merge(
            divKey, TaxPolicy.computeArt30aTotalTax(taxBase, tx.getWithholdingTaxPct(), false), BigDecimal::add);
        totalDividends = totalDividends.add(divBase);
        art30aEvents.add(new TaxPolicy.Art30aPayoutEvent(tx.getExecutedAt(), taxBase, tx.getWithholdingTaxPct()));
        cumulativeExternalCashFlow = cumulativeExternalCashFlow.subtract(divBase);
        if (tx.getExecutedAt() != null) {
          allFlows.add(new MetricsCalculator.CashFlow(divBase.doubleValue(), tx.getExecutedAt()));
        }
        return;
      }
      String series = isBond ? BondValuationEngine.normalizeSeries(tx.getSeriesName()) : null;
      PositionKey key = new PositionKey(tx.getInstrumentId(), series);
      BigDecimal externalFlow = accumulators
          .computeIfAbsent(key, ignored -> new PositionAccumulator())
          .apply(tx, false, isBond);
      cumulativeExternalCashFlow = cumulativeExternalCashFlow.add(externalFlow);
    }

    public Map<PositionKey, PositionAccumulator> accumulators() {
      return accumulators;
    }

    public Map<DividendKey, BigDecimal> dividendsByKey() {
      return dividendsByKey;
    }

    public Map<DividendKey, BigDecimal> dividendTaxByKey() {
      return dividendTaxByKey;
    }

    public BigDecimal totalDividends() {
      return totalDividends;
    }

    public BigDecimal cumulativeExternalCashFlow() {
      return cumulativeExternalCashFlow;
    }

    public PortfolioReplay snapshot() {
      Map<PositionKey, PositionReplayResult> positionResults = new LinkedHashMap<>();
      List<MetricsCalculator.CashFlow> flows = new ArrayList<>(allFlows);
      List<TaxPolicy.CapitalDisposalEvent> disposals = new ArrayList<>();
      for (Map.Entry<PositionKey, PositionAccumulator> entry : accumulators.entrySet()) {
        PositionReplayResult res = entry.getValue().toResult();
        positionResults.put(entry.getKey(), res);
        flows.addAll(res.cashFlows());
        disposals.addAll(entry.getValue().disposalEvents);
      }
      return new PortfolioReplay(
          positionResults,
          new LinkedHashMap<>(dividendsByKey),
          totalDividends,
          flows,
          List.copyOf(disposals),
          List.copyOf(art30aEvents),
          new LinkedHashMap<>(dividendTaxByKey));
    }

    public List<TaxPolicy.CapitalDisposalEvent> disposalEvents() {
      List<TaxPolicy.CapitalDisposalEvent> out = new ArrayList<>();
      for (PositionAccumulator acc : accumulators.values()) {
        out.addAll(acc.disposalEvents);
      }
      return out;
    }

    public List<TaxPolicy.Art30aPayoutEvent> art30aEvents() {
      return art30aEvents;
    }
  }

  public static DividendKey resolveDividendKey(Transaction tx, boolean isBond) {
    String series = BondValuationEngine.normalizeSeries(tx.getSeriesName());
    if (isBond && (series != null || BondValuationEngine.hasBondMetadata(tx))) {
      String family = BondValuationEngine.resolveBondFamily(series, tx.getTermMonths());
      return new DividendKey(tx.getInstrumentId(), family);
    }
    return new DividendKey(tx.getInstrumentId(), null);
  }

  public static PositionReplayResult replayPosition(
      List<Transaction> transactions, boolean strictSell) {
    List<Transaction> sorted =
        (transactions != null ? transactions : List.<Transaction>of())
            .stream().sorted(TX_ORDER).toList();
    PositionAccumulator acc = new PositionAccumulator();
    for (Transaction tx : sorted) {
      acc.apply(tx, strictSell);
    }
    return acc.toResult();
  }

  public record PortfolioReplay(
      Map<PositionKey, PositionReplayResult> positions,
      Map<DividendKey, BigDecimal> dividendsByKey,
      BigDecimal totalDividendsBase,
      List<MetricsCalculator.CashFlow> portfolioCashFlows,
      List<TaxPolicy.CapitalDisposalEvent> disposalEvents,
      List<TaxPolicy.Art30aPayoutEvent> art30aEvents,
      Map<DividendKey, BigDecimal> dividendTaxByKey) {

    public PortfolioReplay(
        Map<PositionKey, PositionReplayResult> positions,
        Map<DividendKey, BigDecimal> dividendsByKey,
        BigDecimal totalDividendsBase,
        List<MetricsCalculator.CashFlow> portfolioCashFlows,
        List<TaxPolicy.CapitalDisposalEvent> disposalEvents,
        List<TaxPolicy.Art30aPayoutEvent> art30aEvents) {
      this(positions, dividendsByKey, totalDividendsBase, portfolioCashFlows, disposalEvents, art30aEvents, Map.of());
    }
  }

  public static BigDecimal computeNetTotal(
      TransactionType type, BigDecimal price, BigDecimal quantity, BigDecimal commission) {
    BigDecimal gross = nonNull(price).multiply(nonNull(quantity));
    BigDecimal comm = nonNull(commission);
    return type == TransactionType.BUY ? gross.add(comm) : gross.subtract(comm);
  }

  public static BigDecimal resolveBuyCostBase(Transaction tx) {
    return resolveBaseAmount(tx, TransactionType.BUY);
  }

  public static BigDecimal resolveProceedsOrDividendBase(Transaction tx) {
    return resolveBaseAmount(tx, TransactionType.SELL);
  }

  public static BigDecimal resolveDividendTaxBase(Transaction tx) {
    BigDecimal feePct = tx.getFxFeePct();
    BigDecimal rate = tx.getExchangeRateToBase();
    if (feePct != null && feePct.signum() > 0 && rate != null && rate.signum() > 0) {
      return computeNetTotal(tx.getTransactionType(), tx.getPrice(), tx.getQuantity(), tx.getCommission())
          .multiply(rate);
    }
    return resolveProceedsOrDividendBase(tx);
  }

  private static BigDecimal resolveBaseAmount(Transaction tx, TransactionType type) {
    if (tx == null) {
      return BigDecimal.ZERO;
    }
    if (tx.getBaseAmount() != null) {
      return tx.getBaseAmount();
    }
    BigDecimal net = computeNetTotal(type, tx.getPrice(), tx.getQuantity(), tx.getCommission());
    if (tx.getExchangeRateToBase() != null && tx.getExchangeRateToBase().compareTo(BigDecimal.ZERO) > 0) {
      return net.multiply(tx.getExchangeRateToBase());
    }
    return net;
  }

  private static BigDecimal nonNull(BigDecimal value) {
    return value != null ? value : BigDecimal.ZERO;
  }
}
