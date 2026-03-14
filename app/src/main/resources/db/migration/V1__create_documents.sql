CREATE TABLE documents
(
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    filename     TEXT        NOT NULL,
    rulebook_id  TEXT        NOT NULL,
    status       TEXT        NOT NULL DEFAULT 'PENDING',
    uploaded_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    total_chunks INTEGER     NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_documents_rulebook_id ON documents (rulebook_id);
CREATE INDEX idx_documents_status ON documents (status);
