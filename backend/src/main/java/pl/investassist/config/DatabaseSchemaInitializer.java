package pl.investassist.config;

import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
public class DatabaseSchemaInitializer implements ApplicationRunner {

  private static final Logger log = LoggerFactory.getLogger(DatabaseSchemaInitializer.class);

  private final DataSource dataSource;
  private final JdbcTemplate jdbcTemplate;

  public DatabaseSchemaInitializer(DataSource dataSource, JdbcTemplate jdbcTemplate) {
    this.dataSource = dataSource;
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    try (Connection connection = dataSource.getConnection()) {
      String productName = connection.getMetaData().getDatabaseProductName();
      if (!productName.equalsIgnoreCase("PostgreSQL")) {
        return;
      }
    } catch (SQLException e) {
      log.error("Failed to determine database product name: {}", e.getMessage());
      return;
    }

    applyPostgresMigrations();
  }

  private void applyPostgresMigrations() {
    try {
      jdbcTemplate.execute("ALTER TABLE instrument DROP CONSTRAINT IF EXISTS instrument_type_check");
      jdbcTemplate.execute("ALTER TABLE instrument ADD CONSTRAINT instrument_type_check CHECK (type IN ('STOCK', 'ETF', 'BOND'))");

      jdbcTemplate.execute("ALTER TABLE position ADD COLUMN IF NOT EXISTS version BIGINT DEFAULT 0 NOT NULL");
      jdbcTemplate.execute("UPDATE position SET version = 0 WHERE version IS NULL");
      jdbcTemplate.execute("ALTER TABLE position ALTER COLUMN version SET NOT NULL");

      jdbcTemplate.execute("UPDATE portfolio SET user_id = (SELECT id FROM users ORDER BY created_at ASC LIMIT 1) WHERE user_id NOT IN (SELECT id FROM users)");
      jdbcTemplate.execute("ALTER TABLE portfolio ALTER COLUMN user_id SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE portfolio ALTER COLUMN name SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE portfolio ALTER COLUMN base_currency SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE portfolio ADD COLUMN IF NOT EXISTS account_type VARCHAR(20) DEFAULT 'STANDARD' NOT NULL");
      jdbcTemplate.execute("UPDATE portfolio SET account_type = 'IKE' WHERE account_type = 'STANDARD' AND LOWER(name) LIKE '%ike%'");
      jdbcTemplate.execute("UPDATE portfolio SET account_type = 'IKZE' WHERE account_type = 'STANDARD' AND LOWER(name) LIKE '%ikze%'");
      jdbcTemplate.execute("ALTER TABLE portfolio DROP CONSTRAINT IF EXISTS chk_portfolio_account_type");
      jdbcTemplate.execute("ALTER TABLE portfolio ADD CONSTRAINT chk_portfolio_account_type CHECK (account_type IN ('STANDARD', 'IKE', 'IKZE'))");
      jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_portfolio_account_type ON portfolio(account_type);");

      jdbcTemplate.execute("UPDATE position SET total_cost_base = quantity * average_buy_price WHERE total_cost_base IS NULL");
      jdbcTemplate.execute("ALTER TABLE position ALTER COLUMN portfolio_id SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE position ALTER COLUMN instrument_id SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE position ALTER COLUMN quantity SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE position ALTER COLUMN average_buy_price SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE position ALTER COLUMN total_cost_base SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE position ALTER COLUMN currency SET NOT NULL");

      jdbcTemplate.execute("UPDATE transaction SET base_amount = quantity * price + COALESCE(commission, 0) WHERE base_amount IS NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN portfolio_id SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN instrument_id SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN transaction_type SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN quantity SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN price SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN commission SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN currency SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN exchange_rate SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN base_amount SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ALTER COLUMN executed_at SET NOT NULL");
      jdbcTemplate.execute("ALTER TABLE transaction ADD COLUMN IF NOT EXISTS exchange_rate_to_base NUMERIC(10, 6)");

      jdbcTemplate.execute(
          "DO '\n"
              + "BEGIN\n"
              + "    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ''fk_portfolio_user'') THEN\n"
              + "        ALTER TABLE portfolio ADD CONSTRAINT fk_portfolio_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;\n"
              + "    END IF;\n"
              + "    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ''fk_position_instrument'') THEN\n"
              + "        ALTER TABLE position ADD CONSTRAINT fk_position_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id);\n"
              + "    END IF;\n"
              + "    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = ''fk_transaction_instrument'') THEN\n"
              + "        ALTER TABLE transaction ADD CONSTRAINT fk_transaction_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id);\n"
              + "    END IF;\n"
              + "END; '");

      jdbcTemplate.execute("ALTER TABLE position DROP CONSTRAINT IF EXISTS uk_position_port_inst_series");
      jdbcTemplate.execute("ALTER TABLE position ADD CONSTRAINT uk_position_port_inst_series UNIQUE NULLS NOT DISTINCT (portfolio_id, instrument_id, series_name)");

      jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_portfolio_user_id ON portfolio(user_id)");
      jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_position_portfolio_id ON position(portfolio_id)");
      jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_position_instrument_id ON position(instrument_id)");
      jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_transaction_portfolio_executed ON transaction(portfolio_id, executed_at DESC)");
      jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_transaction_instrument_id ON transaction(instrument_id)");
      jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_instrument_type ON instrument(type)");
      jdbcTemplate.execute("DROP INDEX IF EXISTS idx_instrument_symbol");
      jdbcTemplate.execute("DROP INDEX IF EXISTS idx_position_port_inst_series");
      jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_transaction_portfolio_inst_exec ON transaction(portfolio_id, instrument_id, executed_at ASC)");
      try {
        jdbcTemplate.execute("CREATE EXTENSION IF NOT EXISTS pg_trgm");
        jdbcTemplate.execute("CREATE INDEX IF NOT EXISTS idx_instrument_search_trgm ON instrument USING gin (symbol gin_trgm_ops, name gin_trgm_ops)");
      } catch (Exception ignored) {
      }
    } catch (Exception e) {
      log.error("Error executing database schema migrations: {}", e.getMessage());
    }
  }
}
