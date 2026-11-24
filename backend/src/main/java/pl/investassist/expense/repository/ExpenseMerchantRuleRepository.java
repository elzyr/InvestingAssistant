package pl.investassist.expense.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import pl.investassist.expense.entity.ExpenseMerchantRule;
import pl.investassist.expense.enums.RuleMatchType;

@Repository
public interface ExpenseMerchantRuleRepository extends JpaRepository<ExpenseMerchantRule, UUID> {

  List<ExpenseMerchantRule> findAllByUserIdOrderByUpdatedAtDesc(UUID userId);

  Optional<ExpenseMerchantRule> findByIdAndUserId(UUID id, UUID userId);

  Optional<ExpenseMerchantRule> findByUserIdAndMatchTypeAndMatchKey(
      UUID userId, RuleMatchType matchType, String matchKey);
}
