package com.rpgmaster.app.adapter.outbound.persistence;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Spring Data JPA repository for document metadata persistence.
 */
public interface DocumentJpaRepository extends JpaRepository<DocumentEntity, UUID> {

    List<DocumentEntity> findByRulebookId(String rulebookId);
}
