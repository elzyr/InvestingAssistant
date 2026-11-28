package pl.investassist.expense.repository;

import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.investassist.expense.entity.ExpenseTransaction;

@Repository
public interface ExpenseTransactionRepository extends JpaRepository<ExpenseTransaction, UUID> {

  List<ExpenseTransaction> findAllByUserIdOrderByTxDateDescCreatedAtDesc(UUID userId);

  Optional<ExpenseTransaction> findByIdAndUserId(UUID id, UUID userId);

  @Query("""
      SELECT t.fingerprint
      FROM ExpenseTransaction t
      WHERE t.userId = :userId AND t.fingerprint IN :fingerprints
      """)
  Set<String> findExistingFingerprints(
      @Param("userId") UUID userId, @Param("fingerprints") Collection<String> fingerprints);

  @Query("""
      SELECT t.fingerprint
      FROM ExpenseTransaction t, ExpenseStatement s
      WHERE s.id = t.statementId AND t.userId = :userId AND t.fingerprint IN :fingerprints
        AND (s.periodEnd < :periodStart OR s.periodStart > :periodEnd)
      """)
  Set<String> findFingerprintsOutsidePeriod(
      @Param("userId") UUID userId,
      @Param("fingerprints") Collection<String> fingerprints,
      @Param("periodStart") LocalDate periodStart,
      @Param("periodEnd") LocalDate periodEnd);

  List<ExpenseTransaction> findAllByUserIdAndMerchantKey(UUID userId, String merchantKey);

  List<ExpenseTransaction> findAllByUserIdAndPhraseKey(UUID userId, String phraseKey);
}
