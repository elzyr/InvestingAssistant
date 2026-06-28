package pl.investassist.portfolio.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.investassist.portfolio.entity.Transaction;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
  List<Transaction> findAllByPortfolioId(UUID portfolioId);
}
