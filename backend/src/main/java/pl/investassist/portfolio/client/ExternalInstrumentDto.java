package pl.investassist.portfolio.client;

public record ExternalInstrumentDto(
    String symbol,
    String name,
    String type,
    String currency
) {}
