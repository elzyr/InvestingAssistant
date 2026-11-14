package pl.investassist.portfolio.valuation;

import java.time.LocalDate;
import java.util.Locale;

public enum Timeframe {
  ONE_MONTH("1M"),
  THREE_MONTHS("3M"),
  SIX_MONTHS("6M"),
  YTD("YTD"),
  ONE_YEAR("1Y"),
  MAX("MAX");

  private final String code;

  Timeframe(String code) {
    this.code = code;
  }

  public static Timeframe orDefault(Timeframe timeframe) {
    return timeframe != null ? timeframe : YTD;
  }

  public static Timeframe fromParam(String raw) {
    if (raw == null || raw.isBlank()) {
      return YTD;
    }
    String clean = raw.trim().toUpperCase(Locale.ROOT);
    for (Timeframe tf : values()) {
      if (tf.code.equals(clean) || tf.name().equals(clean)) {
        return tf;
      }
    }
    throw new IllegalArgumentException("error.invalid_timeframe");
  }

  public LocalDate resolveStartDate(LocalDate today, LocalDate earliestTxDate) {
    return switch (this) {
      case ONE_MONTH -> today.minusMonths(1);
      case THREE_MONTHS -> today.minusMonths(3);
      case SIX_MONTHS -> today.minusMonths(6);
      case YTD -> LocalDate.of(today.getYear(), 1, 1);
      case ONE_YEAR -> today.minusYears(1);
      case MAX -> earliestTxDate != null && !earliestTxDate.isAfter(today) ? earliestTxDate : today;
    };
  }
}
