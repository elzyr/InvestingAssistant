ALTER TABLE transaction
    ADD COLUMN IF NOT EXISTS fx_fee_pct NUMERIC(5, 2);

ALTER TABLE transaction
    DROP CONSTRAINT IF EXISTS fk_transaction_portfolio;

ALTER TABLE transaction
    ADD CONSTRAINT fk_transaction_portfolio
        FOREIGN KEY (portfolio_id) REFERENCES portfolio(id) ON DELETE CASCADE;

ALTER TABLE expense_transaction
    ADD COLUMN IF NOT EXISTS version BIGINT NOT NULL DEFAULT 0;
