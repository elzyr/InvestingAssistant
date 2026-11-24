package pl.investassist.expense.llm;

import java.math.BigDecimal;

public record LlmClassifyInput(
    String id,
    String scrubbedDescription,
    BigDecimal amount,
    String currency,
    String merchantDisplay,
    int statementOccurrences) {

  public LlmClassifyInput {
    statementOccurrences = Math.max(1, statementOccurrences);
  }

  public LlmClassifyInput(
      String id,
      String scrubbedDescription,
      BigDecimal amount,
      String currency) {
    this(id, scrubbedDescription, amount, currency, null, 1);
  }
}
