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
  List<Transaction> findAllByPortfolioIdOrderByExecutedAtDesc(UUID portfolioId);

  Page<Transaction> findAllByPortfolioIdOrderByExecutedAtDesc(UUID portfolioId, Pageable pageable);

  @Query("SELECT t FROM Transaction t WHERE t.portfolio.id = :portfolioId AND t.instrumentId = :instrumentId ORDER BY t.executedAt ASC, t.id ASC")
  List<Transaction> findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(
      @Param("portfolioId") UUID portfolioId, @Param("instrumentId") UUID instrumentId);

  @Query("SELECT t FROM Transaction t WHERE t.portfolio.id = :portfolioId AND t.instrumentId = :instrumentId AND (:seriesName IS NULL AND t.seriesName IS NULL OR t.seriesName = :seriesName) ORDER BY t.executedAt ASC, t.id ASC")
  List<Transaction> findAllByPortfolioIdAndInstrumentIdAndSeriesNameOrderByExecutedAtAsc(
      @Param("portfolioId") UUID portfolioId,
      @Param("instrumentId") UUID instrumentId,
      @Param("seriesName") String seriesName);

  Optional<Transaction> findByIdAndPortfolioId(UUID id, UUID portfolioId);
}
