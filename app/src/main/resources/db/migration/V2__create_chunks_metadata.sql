CREATE TABLE chunks_metadata
(
    id          UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    chunk_id    UUID        NOT NULL UNIQUE,
    document_id UUID        NOT NULL REFERENCES documents (id),
    rulebook_id TEXT        NOT NULL,
    page_number INTEGER     NOT NULL,
    token_count INTEGER     NOT NULL,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_chunks_metadata_document_id ON chunks_metadata (document_id);
CREATE INDEX idx_chunks_metadata_rulebook_id ON chunks_metadata (rulebook_id);
