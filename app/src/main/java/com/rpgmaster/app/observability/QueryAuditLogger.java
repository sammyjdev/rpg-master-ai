package com.rpgmaster.app.observability;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Emits a single JSON line per RAG query to the dedicated {@code rpg.query.audit}
 * logger. Routed separately so operators can ship audit events to a long-term
 * store (eval harness, BigQuery, S3) without polluting general application logs.
 */
@Component
public class QueryAuditLogger {

    private static final Logger audit = LoggerFactory.getLogger("rpg.query.audit");
    private static final Logger log = LoggerFactory.getLogger(QueryAuditLogger.class);

    private final ObjectMapper objectMapper;

    public QueryAuditLogger(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public void log(QueryAuditEvent event) {
        try {
            audit.info(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException exception) {
            log.warn("Failed to serialise QueryAuditEvent — dropping", exception);
        }
    }
}
