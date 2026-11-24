package pl.investassist.expense.llm;

import java.util.List;
import java.util.Map;
import pl.investassist.expense.classifier.MerchantRuleSnapshot;

public interface ExpenseLlmPort {

  boolean isEnabled();

  String providerName();

  String modelName();

  Map<String, LlmCategorySuggestion> classifyUnknowns(List<LlmClassifyInput> items);

  default Map<String, LlmCategorySuggestion> classifyUnknowns(
      List<LlmClassifyInput> items, List<MerchantRuleSnapshot> learnedExamples) {
    return classifyUnknowns(items);
  }

  default Map<String, LlmCategorySuggestion> classifyUnknowns(
      List<LlmClassifyInput> items, LlmClassificationContext context) {
    return classifyUnknowns(items, context != null ? context.learnedExamples() : List.of());
  }
}
