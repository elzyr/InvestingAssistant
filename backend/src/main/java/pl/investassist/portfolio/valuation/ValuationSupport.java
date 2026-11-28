package pl.investassist.portfolio.valuation;

import pl.investassist.common.enums.AssetCategory;
import pl.investassist.common.enums.Currency;
import pl.investassist.instrument.entity.Instrument;
import pl.investassist.portfolio.entity.Transaction;
import pl.investassist.portfolio.port.MarketDataPort;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.function.ToDoubleFunction;

public final class ValuationSupport {

    static final int XIRR_MIN_DAYS = 30;

    private ValuationSupport() {
    }

    public static String normalizeCurrencyCode(String rawCurrency, String fallback) {
        return Currency.normalizeCode(rawCurrency, fallback);
    }

    public static String resolveQuoteCurrency(
            Instrument inst,
            MarketDataPort.MarketQuote quote,
            MarketDataPort.PriceHistory history) {
        if (quote != null && !quote.unavailable() && quote.currency() != null && !quote.currency().isBlank()) {
            return normalizeCurrencyCode(quote.currency(), null);
        }
        if (history != null && history.currency() != null && !history.currency().isBlank()) {
            return normalizeCurrencyCode(history.currency(), null);
        }
        if (inst != null && inst.getCurrency() != null) {
            return inst.getCurrency().name();
        }
        return null;
    }

    public static BigDecimal resolveFxRate(
            String fromCurrency,
            String toCurrency,
            Map<String, MarketDataPort.FxSeries> fxMap,
            LocalDate date) {
        return resolveFxRate(fromCurrency, toCurrency, fxMap, date, false);
    }

    public static BigDecimal resolveFxRate(
            String fromCurrency,
            String toCurrency,
            Map<String, MarketDataPort.FxSeries> fxMap,
            LocalDate date,
            boolean allowSpotFallback) {
        String from = normalizeCurrencyCode(fromCurrency, null);
        String to = normalizeCurrencyCode(toCurrency, null);
        if (from == null || to == null) {
            return null;
        }
        if (from.equalsIgnoreCase(to)) {
            return BigDecimal.ONE;
        }
        if (fxMap == null) {
            return null;
        }
        MarketDataPort.FxSeries series = fxMap.get(from + ":" + to);
        if (series != null) {
            BigDecimal directRate = series.resolveRate(date, allowSpotFallback);
            if (directRate != null && directRate.compareTo(BigDecimal.ZERO) > 0) {
                return directRate;
            }
        }
        MarketDataPort.FxSeries inverse = fxMap.get(to + ":" + from);
        if (inverse != null) {
            BigDecimal invRate = inverse.resolveRate(date, allowSpotFallback);
            if (invRate != null && invRate.compareTo(BigDecimal.ZERO) > 0) {
                return BigDecimal.ONE.divide(invRate, 8, RoundingMode.HALF_UP);
            }
        }
        return null;
    }

    public static BigDecimal requireFxRate(
            String fromCurrency,
            String toCurrency,
            Map<String, MarketDataPort.FxSeries> fxMap,
            LocalDate date) {
        return requireFxRate(fromCurrency, toCurrency, fxMap, date, false);
    }

    public static BigDecimal requireFxRate(
            String fromCurrency,
            String toCurrency,
            Map<String, MarketDataPort.FxSeries> fxMap,
            LocalDate date,
            boolean allowSpotFallback) {
        BigDecimal rate = resolveFxRate(fromCurrency, toCurrency, fxMap, date, allowSpotFallback);
        if (rate == null || rate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new pl.investassist.common.exception.MarketDataUnavailableException(
                    "Exchange rate unavailable for " + fromCurrency + " -> " + toCurrency);
        }
        return rate;
    }

    public static BigDecimal resolveEquityUnitPrice(
            MarketDataPort.MarketQuote quote,
            MarketDataPort.PriceHistory history,
            LocalDate date,
            boolean isToday,
            BigDecimal fallbackPriceNative) {
        if (isToday && quote != null && !quote.unavailable() && quote.price() != null) {
            return quote.price();
        }
        if (history != null) {
            BigDecimal close = history.resolveCloseOnOrBefore(date, null);
            if (close != null) {
                return close;
            }
        }
        if (isToday && fallbackPriceNative != null) {
            return fallbackPriceNative;
        }
        return fallbackPriceNative != null ? fallbackPriceNative : BigDecimal.ZERO;
    }

    public record BondLotsStepResult(
            BigDecimal carryingValueNative,
            BigDecimal completedCouponsNative,
            boolean rateUnavailable,
            List<MetricsCalculator.CashFlow> couponFlowsNative) {
        public BondLotsStepResult(BigDecimal carryingValueNative, BigDecimal completedCouponsNative) {
            this(carryingValueNative, completedCouponsNative, false, List.of());
        }
    }

    public static BondLotsStepResult evaluateBondLotsNative(
            List<PortfolioLedger.FifoLot> lots,
            LocalDateTime targetDateTime,
            java.util.Map<Integer, java.util.Map<Integer, BigDecimal>> monthlyCpiByYear,
            java.util.NavigableMap<LocalDate, BigDecimal> nbpReferenceRates,
            String fallbackSeriesName) {
        if (lots == null || lots.isEmpty()) {
            return new BondLotsStepResult(BigDecimal.ZERO, BigDecimal.ZERO);
        }
        BigDecimal total = BigDecimal.ZERO;
        BigDecimal coupons = BigDecimal.ZERO;
        boolean anyRateUnavailable = false;
        List<MetricsCalculator.CashFlow> couponFlows = new ArrayList<>();
        for (PortfolioLedger.FifoLot lot : lots) {
            if (lot.initialQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String series = lot.seriesName() != null ? lot.seriesName() : fallbackSeriesName;
            BondValuationEngine.LotValuation lotVal = BondValuationEngine.evaluateLot(
                    lot.executedAt(),
                    lot.bondRate(),
                    lot.bondMargin(),
                    monthlyCpiByYear,
                    nbpReferenceRates,
                    targetDateTime,
                    series,
                    lot.termMonths());
            if (lot.remainingQuantity().compareTo(BigDecimal.ZERO) > 0) {
                total = total.add(lot.remainingQuantity().multiply(BigDecimal.valueOf(lotVal.unitPriceNative())));
                anyRateUnavailable |= lotVal.rateUnavailable();
            }
            for (BondValuationEngine.CompletedCoupon c : lotVal.completedCoupons()) {
                BigDecimal qtyAtCoupon = lot.quantityAt(c.couponDate());
                if (qtyAtCoupon.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal coupon = qtyAtCoupon.multiply(BigDecimal.valueOf(c.grossCouponNative()));
                    coupons = coupons.add(coupon);
                    couponFlows.add(new MetricsCalculator.CashFlow(coupon.doubleValue(), c.couponDate().atStartOfDay()));
                }
            }
        }
        return new BondLotsStepResult(total, coupons, anyRateUnavailable, couponFlows);
    }

    public static String resolveCategoryCode(Instrument inst, Transaction sampleTx) {
        return resolveCategoryCode(inst, sampleTx != null ? sampleTx.getSeriesName() : null,
                BondValuationEngine.hasBondMetadata(sampleTx));
    }

    public static String resolveCategoryCode(Instrument inst, String seriesName, boolean hasBondRate) {
        if (BondValuationEngine.isBondPosition(inst, seriesName, hasBondRate)) {
            return AssetCategory.TREASURY_BONDS.name();
        }
        if (inst != null && inst.getAssetCategory() != null) {
            return inst.getAssetCategory().name();
        }
        return AssetCategory.OTHER.name();
    }

    public static BigDecimal scale(BigDecimal value, int scale) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return value.setScale(scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal bd(double value, int scale) {
        if (!Double.isFinite(value)) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return BigDecimal.valueOf(value).setScale(scale, RoundingMode.HALF_UP);
    }

    public static BigDecimal pct(BigDecimal numerator, BigDecimal denominator) {
        return pct(numerator, denominator, 2);
    }

    public static BigDecimal pct(BigDecimal numerator, BigDecimal denominator, int scale) {
        if (numerator == null || denominator == null || denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO.setScale(scale, RoundingMode.HALF_UP);
        }
        return numerator
                .multiply(BigDecimal.valueOf(100))
                .divide(denominator, scale, RoundingMode.HALF_UP);
    }

    public static String normalizeSymbolKey(Instrument inst, UUID fallbackId) {
        if (inst != null && inst.getSymbol() != null && !inst.getSymbol().isBlank()) {
            return inst.getSymbol().trim().toUpperCase(Locale.ROOT);
        }
        return fallbackId != null ? fallbackId.toString() : "";
    }

    static List<MetricsCalculator.CashFlow> withSettledTaxFlow(
            List<MetricsCalculator.CashFlow> baseFlows,
            BigDecimal settledTax,
            LocalDateTime effectiveNow) {
        if (settledTax == null || settledTax.compareTo(BigDecimal.ZERO) <= 0 || effectiveNow == null) {
            return baseFlows;
        }
        List<MetricsCalculator.CashFlow> augmented = new ArrayList<>(baseFlows.size() + 1);
        augmented.addAll(baseFlows);
        augmented.add(new MetricsCalculator.CashFlow(-settledTax.doubleValue(), effectiveNow));
        return augmented;
    }

    static BigDecimal toXirrPct(MetricsCalculator.XirrResult result) {
        if (result == null || result.xirr() == null || !Double.isFinite(result.xirr())) {
            return null;
        }
        return bd(result.xirr() * 100.0, 2);
    }

    static <T> double[] resolveShares(
            List<T> items,
            ToDoubleFunction<T> primaryWeight,
            ToDoubleFunction<T> fallbackWeight) {
        int n = items.size();
        double[] shares = new double[n];
        if (n == 0) {
            return shares;
        }
        if (n == 1) {
            shares[0] = 1.0;
            return shares;
        }
        double sum = 0.0;
        for (int i = 0; i < n; i++) {
            double w = Math.max(0.0, primaryWeight.applyAsDouble(items.get(i)));
            shares[i] = w;
            sum += w;
        }
        if (sum <= 0.0) {
            for (int i = 0; i < n; i++) {
                double w = Math.max(0.0, fallbackWeight.applyAsDouble(items.get(i)));
                shares[i] = w;
                sum += w;
            }
        }
        if (sum <= 0.0) {
            Arrays.fill(shares, 1.0 / n);
        } else {
            for (int i = 0; i < n; i++) {
                shares[i] /= sum;
            }
        }
        return shares;
    }

    static List<BigDecimal> splitAmount(BigDecimal total, double[] shares) {
        int n = shares.length;
        List<BigDecimal> parts = new ArrayList<>(n);
        if (n == 0) {
            return parts;
        }
        if (total == null) {
            for (int i = 0; i < n; i++) {
                parts.add(BigDecimal.ZERO);
            }
            return parts;
        }
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < n; i++) {
            BigDecimal piece = (i == n - 1)
                    ? total.subtract(allocated)
                    : total.multiply(BigDecimal.valueOf(shares[i])).setScale(4, RoundingMode.HALF_UP);
            allocated = allocated.add(piece);
            parts.add(piece);
        }
        return parts;
    }
}
