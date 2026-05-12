-- ============================================================
-- OLTP Transaction Engine Schema
-- ============================================================

CREATE SEQUENCE IF NOT EXISTS transaction_sequence START 1 INCREMENT 50;
CREATE SEQUENCE IF NOT EXISTS ledger_sequence START 1 INCREMENT 1;

CREATE TABLE IF NOT EXISTS ledger_accounts (
    id             BIGINT PRIMARY KEY DEFAULT nextval('ledger_sequence'),
    account_number VARCHAR(20)    NOT NULL UNIQUE,
    owner_name     VARCHAR(100)   NOT NULL,
    balance        NUMERIC(19, 4) NOT NULL DEFAULT 0.0000,
    currency       VARCHAR(3)     NOT NULL DEFAULT 'USD',
    status         VARCHAR(20)    NOT NULL DEFAULT 'ACTIVE',
    created_at     TIMESTAMP      NOT NULL DEFAULT NOW(),
    version        BIGINT         NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS transactions (
    id              BIGINT PRIMARY KEY DEFAULT nextval('transaction_sequence'),
    idempotency_key VARCHAR(64)    NOT NULL UNIQUE,
    account_id      BIGINT         NOT NULL REFERENCES ledger_accounts(id),
    amount          NUMERIC(19, 4) NOT NULL,
    type            VARCHAR(20)    NOT NULL,
    status          VARCHAR(20)    NOT NULL DEFAULT 'PENDING',
    reference_id    VARCHAR(100),
    description     VARCHAR(255),
    created_at      TIMESTAMP      NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP,
    version         BIGINT         NOT NULL DEFAULT 0
);

-- Indexes for high-throughput query patterns
CREATE INDEX IF NOT EXISTS idx_transactions_account_id  ON transactions(account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_status      ON transactions(status);
CREATE INDEX IF NOT EXISTS idx_transactions_created_at  ON transactions(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_transactions_idempotency ON transactions(idempotency_key);
