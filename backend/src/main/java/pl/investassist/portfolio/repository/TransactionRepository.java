package pl.investassist.portfolio.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.investassist.portfolio.entity.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
  Page<Transaction> findAllByPortfolioIdOrderByExecutedAtDesc(UUID portfolioId, Pageable pageable);

  @Query("SELECT t FROM Transaction t WHERE t.portfolio.id = :portfolioId ORDER BY t.executedAt ASC, t.id ASC")
  List<Transaction> findAllByPortfolioIdOrderByExecutedAtAsc(@Param("portfolioId") UUID portfolioId);

  @Query("SELECT t FROM Transaction t WHERE t.portfolio.id = :portfolioId AND t.instrumentId = :instrumentId ORDER BY t.executedAt ASC, t.id ASC")
  List<Transaction> findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(
      @Param("portfolioId") UUID portfolioId, @Param("instrumentId") UUID instrumentId);

  Optional<Transaction> findByIdAndPortfolioId(UUID id, UUID portfolioId);
}
