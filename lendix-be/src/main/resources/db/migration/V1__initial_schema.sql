CREATE EXTENSION IF NOT EXISTS pgcrypto;
CREATE TABLE IF NOT EXISTS customers (id UUID PRIMARY KEY DEFAULT gen_random_uuid(), full_name VARCHAR(160) NOT NULL, dni VARCHAR(8) NOT NULL UNIQUE, phone VARCHAR(30) NOT NULL, email VARCHAR(254), address TEXT, notes TEXT, active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now(), CONSTRAINT customers_dni_format CHECK (dni ~ '^[0-9]{8}$'));
CREATE INDEX IF NOT EXISTS idx_customers_full_name ON customers (lower(full_name));
CREATE INDEX IF NOT EXISTS idx_customers_phone ON customers (phone);

