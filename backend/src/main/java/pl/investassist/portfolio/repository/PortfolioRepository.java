package pl.investassist.portfolio.repository;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.investassist.portfolio.entity.Portfolio;

public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {
  List<Portfolio> findAllByUserId(UUID userId);
}
