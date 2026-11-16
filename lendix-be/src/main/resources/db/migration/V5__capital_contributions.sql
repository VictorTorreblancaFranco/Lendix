CREATE TABLE IF NOT EXISTS capital_contributions (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    contributor VARCHAR(80) NOT NULL,
    amount NUMERIC(14,2) NOT NULL CHECK (amount > 0),
    contribution_date DATE NOT NULL DEFAULT CURRENT_DATE,
    returned_amount NUMERIC(14,2) NOT NULL DEFAULT 0 CHECK (returned_amount >= 0),
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT capital_returned_not_over_amount CHECK (returned_amount <= amount)
);

CREATE INDEX IF NOT EXISTS idx_capital_contributions_contributor
    ON capital_contributions(contributor);
