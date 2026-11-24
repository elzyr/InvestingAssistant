package pl.investassist.expense.dto.request;

import java.time.LocalDate;
import java.util.UUID;

public record ReclassifyExpensesRequest(
    LocalDate from,
    LocalDate to,
    UUID statementId,
    Boolean includeManualOverrides) {

  public ReclassifyExpensesRequest {
    includeManualOverrides = Boolean.TRUE.equals(includeManualOverrides);
  }
}
