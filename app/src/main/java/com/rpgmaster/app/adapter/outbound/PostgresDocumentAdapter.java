package com.rpgmaster.app.adapter.outbound;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Repository;

import com.rpgmaster.app.adapter.outbound.persistence.DocumentEntity;
import com.rpgmaster.app.adapter.outbound.persistence.DocumentJpaRepository;
import com.rpgmaster.app.application.port.DocumentRepository;
import com.rpgmaster.domain.Document.IngestionStatus;

/**
 * PostgreSQL adapter for document lifecycle persistence.
 * Implements {@link DocumentRepository} via Spring Data JPA.
 *
 * <p>All operations participate in the calling {@code @Transactional} boundary
 * from {@code IngestionUseCase}. JPA first-level cache ensures {@code findById}
 * calls within the same transaction do not incur extra round-trips.
 */
@Repository
public class PostgresDocumentAdapter implements DocumentRepository {

    private final DocumentJpaRepository jpaRepository;

    public PostgresDocumentAdapter(DocumentJpaRepository jpaRepository) {
        this.jpaRepository = jpaRepository;
    }

    @Override
    public void create(String documentId, String filename, String rulebookId, Instant uploadedAt) {
        jpaRepository.save(new DocumentEntity(UUID.fromString(documentId), filename, rulebookId,
                IngestionStatus.PROCESSING, uploadedAt, 0));
    }

    @Override
    public void complete(String documentId, int totalChunks) {
        jpaRepository.findById(UUID.fromString(documentId)).ifPresent(entity -> {
            entity.setStatus(IngestionStatus.COMPLETED);
            entity.setTotalChunks(totalChunks);
            jpaRepository.save(entity);
        });
    }

    @Override
    public void fail(String documentId) {
        jpaRepository.findById(UUID.fromString(documentId)).ifPresent(entity -> {
            entity.setStatus(IngestionStatus.FAILED);
            jpaRepository.save(entity);
        });
    }

    @Override
    public List<String> listRulebookIds() {
        return jpaRepository.findAll().stream()
                .filter(entity -> entity.getStatus() == IngestionStatus.COMPLETED)
                .map(DocumentEntity::getRulebookId)
                .distinct()
                .sorted()
                .toList();
    }
}
