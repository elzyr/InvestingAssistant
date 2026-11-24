CREATE TABLE IF NOT EXISTS expense_statement (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    file_name VARCHAR(255) NOT NULL,
    file_hash_sha256 VARCHAR(64) NOT NULL,
    format VARCHAR(16) NOT NULL,
    bank_profile VARCHAR(32) NOT NULL,
    account_label VARCHAR(120),
    currency VARCHAR(8) NOT NULL,
    period_start DATE,
    period_end DATE,
    opening_balance NUMERIC(18, 2),
    closing_balance NUMERIC(18, 2),
    calculated_inflows NUMERIC(18, 2) NOT NULL DEFAULT 0,
    calculated_outflows NUMERIC(18, 2) NOT NULL DEFAULT 0,
    reconciliation_status VARCHAR(20) NOT NULL,
    reconciliation_delta NUMERIC(18, 2),
    parsed_count INTEGER NOT NULL DEFAULT 0,
    skipped_duplicates INTEGER NOT NULL DEFAULT 0,
    created_at TIMESTAMP(6) NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_expense_statement_user_created
    ON expense_statement(user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_expense_statement_user_hash
    ON expense_statement(user_id, file_hash_sha256);

CREATE TABLE IF NOT EXISTS expense_transaction (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    statement_id UUID NOT NULL REFERENCES expense_statement(id) ON DELETE CASCADE,
    tx_date DATE NOT NULL,
    booking_date DATE,
    raw_description TEXT NOT NULL,
    merchant_key VARCHAR(160) NOT NULL,
    merchant_display VARCHAR(160) NOT NULL,
    phrase_key VARCHAR(160),
    amount NUMERIC(18, 2) NOT NULL,
    currency VARCHAR(8) NOT NULL,
    base_amount NUMERIC(18, 2) NOT NULL,
    base_currency VARCHAR(8) NOT NULL,
    fx_rate NUMERIC(18, 8) NOT NULL DEFAULT 1,
    fx_degraded BOOLEAN NOT NULL DEFAULT FALSE,
    flow_kind VARCHAR(32) NOT NULL,
    category VARCHAR(32) NOT NULL,
    classification_source VARCHAR(32) NOT NULL,
    confidence NUMERIC(4, 2) NOT NULL,
    is_recurring BOOLEAN NOT NULL DEFAULT FALSE,
    excluded BOOLEAN NOT NULL DEFAULT FALSE,
    fingerprint VARCHAR(64) NOT NULL,
    user_note VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_expense_tx_user_fp UNIQUE (user_id, fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_expense_tx_user_date
    ON expense_transaction(user_id, tx_date DESC);
CREATE INDEX IF NOT EXISTS idx_expense_tx_statement_id
    ON expense_transaction(statement_id);
CREATE INDEX IF NOT EXISTS idx_expense_tx_user_merchant
    ON expense_transaction(user_id, merchant_key);

CREATE TABLE IF NOT EXISTS expense_merchant_rule (
    id UUID PRIMARY KEY,
    user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    match_type VARCHAR(16) NOT NULL,
    match_key VARCHAR(160) NOT NULL,
    display_label VARCHAR(160) NOT NULL,
    category VARCHAR(32) NOT NULL,
    flow_kind VARCHAR(32) NOT NULL,
    source VARCHAR(32) NOT NULL,
    hit_count INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP(6) NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_expense_rule_user_key UNIQUE (user_id, match_type, match_key)
);
