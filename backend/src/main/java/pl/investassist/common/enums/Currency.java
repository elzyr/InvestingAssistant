package pl.investassist.common.enums;

import java.util.Locale;

public enum Currency {
  PLN,
  EUR,
  USD,
  GBP,
  CHF;

  public static String normalizeCode(String rawCurrency, String fallback) {
    if (rawCurrency == null || rawCurrency.isBlank()) {
      return fallback;
    }
    return rawCurrency.trim().toUpperCase(Locale.ROOT);
  }
}
