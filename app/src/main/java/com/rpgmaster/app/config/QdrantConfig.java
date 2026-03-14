package com.rpgmaster.app.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Qdrant client configuration.
 * Connects via gRPC (port 6334) for better performance than HTTP (6333).
 */
@Configuration
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(
            @Value("${qdrant.host:localhost}") String host,
            @Value("${qdrant.port:6334}") int port
    ) {
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(host, port, false).build()
        );
    }
}
