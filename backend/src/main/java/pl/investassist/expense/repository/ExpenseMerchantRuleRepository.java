package pl.investassist.expense.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import pl.investassist.expense.entity.ExpenseMerchantRule;
import pl.investassist.expense.enums.RuleMatchType;

@Repository
public interface ExpenseMerchantRuleRepository extends JpaRepository<ExpenseMerchantRule, UUID> {

  List<ExpenseMerchantRule> findAllByUserIdOrderByUpdatedAtDesc(UUID userId);

  Optional<ExpenseMerchantRule> findByIdAndUserId(UUID id, UUID userId);

  Optional<ExpenseMerchantRule> findByUserIdAndMatchTypeAndMatchKey(
      UUID userId, RuleMatchType matchType, String matchKey);

  @Modifying
  @Query(
      value = "INSERT INTO expense_merchant_rule (id, user_id, match_type, match_key, display_label, category, flow_kind, source, hit_count, created_at, updated_at) VALUES (:id, :userId, :matchType, :matchKey, :displayLabel, :category, :flowKind, :source, 1, NOW(), NOW()) ON CONFLICT (user_id, match_type, match_key) DO UPDATE SET display_label = EXCLUDED.display_label, category = EXCLUDED.category, flow_kind = EXCLUDED.flow_kind, source = EXCLUDED.source, hit_count = expense_merchant_rule.hit_count + 1, updated_at = NOW()",
      nativeQuery = true)
  int upsertRule(
      @Param("id") UUID id,
      @Param("userId") UUID userId,
      @Param("matchType") String matchType,
      @Param("matchKey") String matchKey,
      @Param("displayLabel") String displayLabel,
      @Param("category") String category,
      @Param("flowKind") String flowKind,
      @Param("source") String source);
}
