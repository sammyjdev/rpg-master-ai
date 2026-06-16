package com.rpgmaster.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single source of truth for Qdrant connection + collection.
 * Bound from {@code qdrant.*} in application.yml.
 *
 * @param host       Qdrant host (default localhost)
 * @param port       gRPC port (default 6334)
 * @param collection collection name (default rpg-chunks)
 */
@ConfigurationProperties(prefix = "qdrant")
public record QdrantProperties(
        String host,
        Integer port,
        String collection
) {
    public QdrantProperties {
        if (host == null || host.isBlank()) host = "localhost";
        if (port == null) port = 6334;
        if (collection == null || collection.isBlank()) collection = "rpg-chunks";
    }
}
