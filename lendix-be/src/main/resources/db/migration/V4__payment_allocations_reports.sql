ALTER TABLE payments ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);
ALTER TABLE payments ADD COLUMN IF NOT EXISTS reversed_at TIMESTAMPTZ;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS reversal_reason TEXT;
ALTER TABLE payments ADD COLUMN IF NOT EXISTS installment_id UUID REFERENCES installments(id);
ALTER TABLE loans ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(100);
CREATE UNIQUE INDEX IF NOT EXISTS uq_loans_idempotency_key ON loans(idempotency_key) WHERE idempotency_key IS NOT NULL;
ALTER TABLE installments ADD COLUMN IF NOT EXISTS principal_paid NUMERIC(14,2) NOT NULL DEFAULT 0;
ALTER TABLE installments ADD COLUMN IF NOT EXISTS interest_paid NUMERIC(14,2) NOT NULL DEFAULT 0;
CREATE UNIQUE INDEX IF NOT EXISTS uq_payments_idempotency_key ON payments(idempotency_key) WHERE idempotency_key IS NOT NULL;

CREATE TABLE IF NOT EXISTS payment_allocations (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    payment_id UUID NOT NULL REFERENCES payments(id) ON DELETE CASCADE,
    installment_id UUID NOT NULL REFERENCES installments(id),
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    principal_paid NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (principal_paid >= 0),
    interest_paid NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (interest_paid >= 0),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(payment_id, installment_id)
);
CREATE INDEX IF NOT EXISTS idx_payment_allocations_installment ON payment_allocations(installment_id);

ALTER TABLE notifications ADD COLUMN IF NOT EXISTS kind VARCHAR(30) NOT NULL DEFAULT 'PAYMENT_REMINDER';
CREATE UNIQUE INDEX IF NOT EXISTS uq_notifications_reference ON notifications(channel, reference_id, kind) WHERE reference_id IS NOT NULL;
