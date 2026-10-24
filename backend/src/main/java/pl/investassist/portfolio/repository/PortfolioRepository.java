package pl.investassist.portfolio.repository;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import pl.investassist.portfolio.entity.Portfolio;

public interface PortfolioRepository extends JpaRepository<Portfolio, UUID> {
  List<Portfolio> findAllByUserId(UUID userId);

  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("SELECT p FROM Portfolio p WHERE p.id = :id")
  Optional<Portfolio> findByIdWithLock(@Param("id") UUID id);
}
