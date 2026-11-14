package pl.investassist.portfolio.valuation;

import java.util.Locale;

public enum TaxMode {
  NETTO,
  BRUTTO;

  public static TaxMode orDefault(TaxMode mode) {
    return mode != null ? mode : NETTO;
  }

  public static TaxMode fromParam(String raw) {
    if (raw == null || raw.isBlank()) {
      return NETTO;
    }
    try {
      return TaxMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("error.invalid_tax_mode");
    }
  }
}
