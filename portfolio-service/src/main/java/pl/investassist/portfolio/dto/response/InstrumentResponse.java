package pl.investassist.portfolio.dto.response;

import pl.investassist.portfolio.enums.Currency;
import pl.investassist.portfolio.enums.InstrumentType;

import java.util.UUID;

public record InstrumentResponse(
    UUID id, String symbol, String name, InstrumentType type, Currency currency) {}
