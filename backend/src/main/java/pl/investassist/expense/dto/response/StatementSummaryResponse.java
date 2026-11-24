package pl.investassist.expense.dto.response;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.ReconciliationStatus;
import pl.investassist.expense.enums.StatementFormat;

public record StatementSummaryResponse(
    UUID id,
    String fileName,
    StatementFormat format,
    BankProfile bankProfile,
    String accountLabel,
    String currency,
    LocalDate periodStart,
    LocalDate periodEnd,
    ReconciliationStatus reconciliationStatus,
    BigDecimal reconciliationDelta,
    int savedCount,
    int skippedDuplicates,
    Instant createdAt) {}
