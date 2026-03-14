package com.rpgmaster.app.adapter.outbound.persistence;

import java.time.Instant;
import java.util.UUID;

import com.rpgmaster.domain.Document.IngestionStatus;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

/**
 * JPA entity for the {@code documents} table.
 * Maps to the {@link com.rpgmaster.domain.Document} record for the application layer.
 */
@Entity
@Table(name = "documents")
public class DocumentEntity {

    @Id
    @Column(columnDefinition = "UUID")
    private UUID id;

    @Column(nullable = false)
    private String filename;

    @Column(name = "rulebook_id", nullable = false)
    private String rulebookId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private IngestionStatus status;

    @Column(name = "uploaded_at", nullable = false)
    private Instant uploadedAt;

    @Column(name = "total_chunks", nullable = false)
    private int totalChunks;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected DocumentEntity() {}

    public DocumentEntity(UUID id, String filename, String rulebookId,
                          IngestionStatus status, Instant uploadedAt, int totalChunks) {
        this.id = id;
        this.filename = filename;
        this.rulebookId = rulebookId;
        this.status = status;
        this.uploadedAt = uploadedAt;
        this.totalChunks = totalChunks;
        this.createdAt = Instant.now();
        this.updatedAt = Instant.now();
    }

    @PreUpdate
    void onUpdate() {
        this.updatedAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getFilename() { return filename; }
    public String getRulebookId() { return rulebookId; }
    public IngestionStatus getStatus() { return status; }
    public Instant getUploadedAt() { return uploadedAt; }
    public int getTotalChunks() { return totalChunks; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }

    public void setStatus(IngestionStatus status) { this.status = status; }
    public void setTotalChunks(int totalChunks) { this.totalChunks = totalChunks; }
}
