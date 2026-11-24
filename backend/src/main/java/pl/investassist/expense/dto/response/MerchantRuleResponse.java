package pl.investassist.expense.dto.response;

import java.time.Instant;
import java.util.UUID;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;
import pl.investassist.expense.enums.RuleMatchType;

public record MerchantRuleResponse(
    UUID id,
    RuleMatchType matchType,
    String matchKey,
    String displayLabel,
    ExpenseCategory category,
    FlowKind flowKind,
    String source,
    int hitCount,
    Instant updatedAt) {}
