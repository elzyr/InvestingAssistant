package pl.investassist.expense.dto.request;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pl.investassist.expense.enums.ExpenseCategory;
import pl.investassist.expense.enums.FlowKind;

public record UpdateExpenseTransactionRequest(
    @NotNull ExpenseCategory category,
    FlowKind flowKind,
    Boolean excluded,
    @Size(max = 255) String userNote,
    Boolean rememberRule,
    Boolean applyToMatching) {

  public UpdateExpenseTransactionRequest {
    rememberRule = Boolean.TRUE.equals(rememberRule);
    applyToMatching = Boolean.TRUE.equals(applyToMatching);
  }
}
