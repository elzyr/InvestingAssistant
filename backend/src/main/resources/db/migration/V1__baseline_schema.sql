CREATE TABLE IF NOT EXISTS users (
    id UUID PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    first_name VARCHAR(255),
    last_name VARCHAR(255),
    role VARCHAR(255) NOT NULL,
    base_currency VARCHAR(255) NOT NULL,
    created_at TIMESTAMP(6),
    updated_at TIMESTAMP(6)
);

CREATE TABLE IF NOT EXISTS instrument (
    id UUID PRIMARY KEY,
    symbol VARCHAR(255) NOT NULL UNIQUE,
    name VARCHAR(255) NOT NULL,
    type VARCHAR(255) NOT NULL,
    currency VARCHAR(255) NOT NULL,
    CONSTRAINT instrument_type_check CHECK (type IN ('STOCK', 'ETF', 'BOND'))
);

CREATE TABLE IF NOT EXISTS portfolio (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL,
    name VARCHAR(255) NOT NULL,
    description VARCHAR(255),
    base_currency VARCHAR(255) NOT NULL,
    account_type VARCHAR(20) NOT NULL DEFAULT 'STANDARD',
    created_at TIMESTAMP(6),
    CONSTRAINT fk_portfolio_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE,
    CONSTRAINT chk_portfolio_account_type CHECK (account_type IN ('STANDARD', 'IKE', 'IKZE'))
);

CREATE TABLE IF NOT EXISTS transaction (
    id UUID PRIMARY KEY,
    portfolio_id UUID NOT NULL,
    instrument_id UUID NOT NULL,
    transaction_type VARCHAR(255) NOT NULL,
    quantity NUMERIC(38, 8) NOT NULL,
    price NUMERIC(38, 6) NOT NULL,
    commission NUMERIC(38, 6) NOT NULL,
    currency VARCHAR(255) NOT NULL,
    exchange_rate NUMERIC(38, 8) NOT NULL,
    base_amount NUMERIC(38, 4) NOT NULL,
    exchange_rate_to_base NUMERIC(10, 6),
    executed_at TIMESTAMP(6) NOT NULL,
    series_name VARCHAR(50),
    bond_rate NUMERIC(10, 4),
    bond_margin NUMERIC(10, 4),
    term_months INTEGER,
    CONSTRAINT fk_transaction_portfolio FOREIGN KEY (portfolio_id) REFERENCES portfolio(id),
    CONSTRAINT fk_transaction_instrument FOREIGN KEY (instrument_id) REFERENCES instrument(id)
);

CREATE INDEX IF NOT EXISTS idx_portfolio_user_id ON portfolio(user_id);
CREATE INDEX IF NOT EXISTS idx_portfolio_account_type ON portfolio(account_type);
CREATE INDEX IF NOT EXISTS idx_transaction_portfolio_executed ON transaction(portfolio_id, executed_at DESC);
CREATE INDEX IF NOT EXISTS idx_transaction_instrument_id ON transaction(instrument_id);
CREATE INDEX IF NOT EXISTS idx_tx_port_inst_time ON transaction(portfolio_id, instrument_id, executed_at);
CREATE INDEX IF NOT EXISTS idx_instrument_type ON instrument(type);

DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_trgm;
    CREATE INDEX IF NOT EXISTS idx_instrument_search_trgm ON instrument USING gin (symbol gin_trgm_ops, name gin_trgm_ops);
EXCEPTION WHEN OTHERS THEN
    NULL;
END $$;
