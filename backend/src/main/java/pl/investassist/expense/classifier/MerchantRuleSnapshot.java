package pl.investassist.expense.classifier;

import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.RuleMatchType;

public record MerchantRuleSnapshot(
    RuleMatchType matchType,
    String matchKey,
    ExpenseCategory category,
    FlowKind flowKind) {}
