DROP INDEX IF EXISTS idx_portfolio_account_type;
ALTER TABLE portfolio DROP CONSTRAINT IF EXISTS chk_portfolio_account_type;
ALTER TABLE portfolio DROP COLUMN IF EXISTS account_type;

UPDATE transaction
SET series_name = UPPER(TRIM(series_name))
WHERE series_name IS NOT NULL AND series_name <> UPPER(TRIM(series_name));

ALTER TABLE transaction
    ALTER COLUMN exchange_rate TYPE NUMERIC(18, 8),
    ALTER COLUMN exchange_rate_to_base TYPE NUMERIC(18, 8),
    ADD COLUMN IF NOT EXISTS instrument_to_tx_rate NUMERIC(18, 8),
    ADD COLUMN IF NOT EXISTS withholding_tax_pct NUMERIC(5, 2);

UPDATE transaction
SET instrument_to_tx_rate = exchange_rate
WHERE instrument_to_tx_rate IS NULL;

ALTER TABLE instrument
    ADD COLUMN IF NOT EXISTS asset_category VARCHAR(32);

DO $$
BEGIN
    IF to_regclass('market_data.daily_price_history') IS NOT NULL THEN
        DELETE FROM market_data.daily_price_history
        WHERE close_price IS NULL
           OR close_price <= 0
           OR currency IN ('GBp', 'GBX')
           OR UPPER(symbol) LIKE '%.L'
           OR (currency = 'PLN' AND UPPER(symbol) NOT LIKE '%.WA');
    END IF;
END $$;
