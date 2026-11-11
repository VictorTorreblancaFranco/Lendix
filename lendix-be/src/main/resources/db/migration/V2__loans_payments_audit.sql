CREATE TABLE IF NOT EXISTS loans (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), customer_id UUID NOT NULL REFERENCES customers(id),
    principal NUMERIC(14,2) NOT NULL CHECK (principal > 0), interest_rate NUMERIC(8,4) NOT NULL CHECK (interest_rate >= 0),
    rate_period VARCHAR(20) NOT NULL DEFAULT 'MONTHLY', term_count INTEGER NOT NULL CHECK (term_count > 0),
    payment_frequency VARCHAR(20) NOT NULL DEFAULT 'MONTHLY', disbursement_date DATE NOT NULL DEFAULT CURRENT_DATE,
    first_due_date DATE NOT NULL, total_due NUMERIC(14,2) NOT NULL, outstanding_principal NUMERIC(14,2) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE', notes TEXT, version BIGINT NOT NULL DEFAULT 0,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_loans_customer_status ON loans(customer_id,status);

CREATE TABLE IF NOT EXISTS payments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), loan_id UUID NOT NULL REFERENCES loans(id), paid_at DATE NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0), principal_paid NUMERIC(14,2) NOT NULL CHECK (principal_paid >= 0),
    interest_paid NUMERIC(14,2) NOT NULL CHECK (interest_paid >= 0), payment_method VARCHAR(30) NOT NULL DEFAULT 'CASH', notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_payments_loan_date ON payments(loan_id,paid_at);

CREATE TABLE IF NOT EXISTS audit_log (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), action VARCHAR(40) NOT NULL, entity_type VARCHAR(40) NOT NULL,
    entity_id UUID, details TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS installments (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), loan_id UUID NOT NULL REFERENCES loans(id), installment_number INTEGER NOT NULL,
    due_date DATE NOT NULL, principal_due NUMERIC(14,2) NOT NULL, interest_due NUMERIC(14,2) NOT NULL, amount_due NUMERIC(14,2) NOT NULL,
    amount_paid NUMERIC(14,2) NOT NULL DEFAULT 0, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', UNIQUE(loan_id,installment_number)
);

CREATE TABLE IF NOT EXISTS loan_events (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), loan_id UUID NOT NULL REFERENCES loans(id),
    event_type VARCHAR(30) NOT NULL, previous_due_date DATE, new_due_date DATE, charge NUMERIC(14,2) NOT NULL DEFAULT 0,
    notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS cash_movements (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), movement_type VARCHAR(20) NOT NULL, amount NUMERIC(14,2) NOT NULL CHECK(amount > 0),
    source VARCHAR(40) NOT NULL, reference_id UUID, notes TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS app_users (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), username VARCHAR(80) NOT NULL UNIQUE, password_hash VARCHAR(255) NOT NULL,
    role VARCHAR(30) NOT NULL DEFAULT 'ADMIN', active BOOLEAN NOT NULL DEFAULT TRUE, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
