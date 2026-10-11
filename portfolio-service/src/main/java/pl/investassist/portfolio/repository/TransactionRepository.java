package pl.investassist.portfolio.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.investassist.portfolio.entity.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
  List<Transaction> findAllByPortfolioIdOrderByExecutedAtDesc(UUID portfolioId);

  List<Transaction> findAllByPortfolioIdAndInstrumentIdOrderByExecutedAtAsc(
      UUID portfolioId, UUID instrumentId);

  Optional<Transaction> findByIdAndPortfolioId(UUID id, UUID portfolioId);
}
