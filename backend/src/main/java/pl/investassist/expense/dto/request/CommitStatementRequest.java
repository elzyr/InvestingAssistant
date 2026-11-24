package pl.investassist.expense.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import pl.investassist.expense.enums.BankProfile;
import pl.investassist.expense.enums.ReconciliationStatus;
import pl.investassist.expense.enums.StatementFormat;

public record CommitStatementRequest(
    @NotBlank @Size(max = 255) String fileName,
    @NotBlank @Size(max = 64) String fileHashSha256,
    @NotNull StatementFormat format,
    @NotNull BankProfile bankProfile,
    @Size(max = 120) String accountLabel,
    @NotBlank @Size(max = 8) String currency,
    LocalDate periodStart,
    LocalDate periodEnd,
    BigDecimal openingBalance,
    BigDecimal closingBalance,
    BigDecimal calculatedInflows,
    BigDecimal calculatedOutflows,
    @NotNull ReconciliationStatus reconciliationStatus,
    BigDecimal reconciliationDelta,
    @NotNull List<@Valid CommitTransactionItem> transactions) {}
