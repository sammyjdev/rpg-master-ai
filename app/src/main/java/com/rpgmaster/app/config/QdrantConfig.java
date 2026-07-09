package com.rpgmaster.app.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Qdrant client configuration.
 * Connects via gRPC (port 6334) for better performance than HTTP (6333).
 */
@Configuration
@EnableConfigurationProperties(QdrantProperties.class)
public class QdrantConfig {

    @Bean
    public QdrantClient qdrantClient(QdrantProperties props) {
        return new QdrantClient(
                QdrantGrpcClient.newBuilder(props.host(), props.port(), false).build()
        );
    }
}