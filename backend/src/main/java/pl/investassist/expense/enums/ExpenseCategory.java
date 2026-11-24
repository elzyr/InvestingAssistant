package pl.investassist.expense.enums;

import java.util.Locale;

public enum ExpenseCategory {
  GROCERIES(FlowKind.EXPENSE),
  DINING(FlowKind.EXPENSE),
  HOUSING(FlowKind.EXPENSE),
  UTILITIES(FlowKind.EXPENSE),
  TELECOM(FlowKind.EXPENSE),
  HOME_GARDEN(FlowKind.EXPENSE),
  FUEL(FlowKind.EXPENSE),
  TRANSPORT(FlowKind.EXPENSE),
  CAR_MAINTENANCE(FlowKind.EXPENSE),
  HEALTH(FlowKind.EXPENSE),
  FITNESS_SPORT(FlowKind.EXPENSE),
  CLOTHING(FlowKind.EXPENSE),
  BEAUTY_CARE(FlowKind.EXPENSE),
  ELECTRONICS(FlowKind.EXPENSE),
  SHOPPING(FlowKind.EXPENSE),
  ENTERTAINMENT(FlowKind.EXPENSE),
  SUBSCRIPTIONS(FlowKind.EXPENSE),
  TRAVEL(FlowKind.EXPENSE),
  EDUCATION(FlowKind.EXPENSE),
  PETS(FlowKind.EXPENSE),
  GIFTS(FlowKind.EXPENSE),
  SERVICES_FEES(FlowKind.EXPENSE),
  OTHER_EXPENSE(FlowKind.EXPENSE),
  INCOME(FlowKind.INCOME),
  INVESTMENTS_SAVINGS(FlowKind.INVESTMENT_SAVING),
  INTERNAL_TRANSFER(FlowKind.INTERNAL_TRANSFER),
  REFUND(FlowKind.REFUND);

  private final FlowKind defaultFlowKind;

  ExpenseCategory(FlowKind defaultFlowKind) {
    this.defaultFlowKind = defaultFlowKind;
  }

  public FlowKind defaultFlowKind() {
    return defaultFlowKind;
  }

  public boolean isConsumption() {
    return defaultFlowKind == FlowKind.EXPENSE;
  }

  public static ExpenseCategory fromStringOrNull(String raw) {
    if (raw == null || raw.isBlank()) {
      return null;
    }
    try {
      return ExpenseCategory.valueOf(raw.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
