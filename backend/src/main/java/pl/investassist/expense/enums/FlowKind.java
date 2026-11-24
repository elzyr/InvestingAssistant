package pl.investassist.expense.enums;

import java.util.Locale;

public enum FlowKind {
  EXPENSE,
  INCOME,
  INVESTMENT_SAVING,
  INTERNAL_TRANSFER,
  REFUND;

  public static FlowKind fromStringOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return FlowKind.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
