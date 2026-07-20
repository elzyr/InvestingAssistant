package pl.investassist.portfolio.dto.response;

import java.math.BigDecimal;
import pl.investassist.portfolio.enums.Currency;

public record ExchangeRateResponse(Currency from, Currency to, BigDecimal rate) {}
