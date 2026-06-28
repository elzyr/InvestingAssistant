package pl.investassist.portfolio.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import pl.investassist.portfolio.entity.Position;

public interface PositionRepository extends JpaRepository<Position, UUID> {
  List<Position> findAllByPortfolioId(UUID portfolioId);

  Optional<Position> findByPortfolioIdAndInstrumentId(UUID portfolioId, UUID instrumentId);
}
