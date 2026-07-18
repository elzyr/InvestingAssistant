package pl.investassist.instrument.dto.response;

import pl.investassist.instrument.enums.Currency;
import pl.investassist.instrument.enums.InstrumentType;

import java.util.UUID;

public record InstrumentResponse(
    UUID id, String symbol, String name, InstrumentType type, Currency currency) {}
