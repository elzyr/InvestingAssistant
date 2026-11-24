package pl.investassist.expense.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.investassist.expense.entity.ExpenseStatement;

@Repository
public interface ExpenseStatementRepository extends JpaRepository<ExpenseStatement, UUID> {

  List<ExpenseStatement> findAllByUserIdOrderByCreatedAtDesc(UUID userId);

  Optional<ExpenseStatement> findByIdAndUserId(UUID id, UUID userId);

  boolean existsByUserIdAndFileHashSha256(UUID userId, String fileHashSha256);
}
