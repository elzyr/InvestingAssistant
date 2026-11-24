package pl.investassist.expense.llm;

import java.util.List;
import java.util.Objects;
import pl.investassist.expense.classifier.MerchantRuleSnapshot;

public record LlmClassificationContext(
    String accountOwnerName,
    List<String> statementMerchants,
    List<MerchantRuleSnapshot> learnedExamples) {

  public LlmClassificationContext {
    statementMerchants = statementMerchants == null
        ? List.of()
        : statementMerchants.stream().filter(Objects::nonNull).toList();
    learnedExamples = learnedExamples == null
        ? List.of()
        : learnedExamples.stream().filter(Objects::nonNull).toList();
  }
}
