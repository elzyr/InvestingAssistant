package pl.investassist.expense.classifier;

public record NormalizedMerchant(
    String merchantKey,
    String merchantDisplay,
    String phraseKey,
    boolean intermediary,
    String normalizedDescription) {}
