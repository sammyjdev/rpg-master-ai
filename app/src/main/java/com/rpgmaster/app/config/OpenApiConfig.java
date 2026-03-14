package com.rpgmaster.app.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;

/**
 * OpenAPI 3.1 metadata for Swagger UI and spec generation.
 * Swagger UI available at {@code /swagger-ui.html} when the {@code api} profile is active.
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI rpgMasterOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("RPG Master AI API")
                        .description("OpenAI-compatible RAG API for RPG rulebook Q&A. "
                                + "Supports both blocking and SSE streaming responses.")
                        .version("1.0.0")
                        .contact(new Contact().name("Sammy")));
    }
}
