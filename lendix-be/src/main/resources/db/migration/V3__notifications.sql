CREATE TABLE IF NOT EXISTS notifications (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(), channel VARCHAR(20) NOT NULL, recipient VARCHAR(120) NOT NULL,
    message TEXT NOT NULL, status VARCHAR(20) NOT NULL DEFAULT 'PENDING', reference_id UUID,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), sent_at TIMESTAMPTZ
);
