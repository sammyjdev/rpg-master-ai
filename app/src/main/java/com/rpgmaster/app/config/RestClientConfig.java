package com.rpgmaster.app.config;

import java.time.Duration;

import org.springframework.beans.factory.config.ConfigurableBeanFactory;
import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Scope;
import org.springframework.web.client.RestClient;

/**
 * Spring Boot's {@code RestClientAutoConfiguration} only provides a
 * {@link RestClient.Builder} bean for non-reactive web applications; this app
 * runs on {@code spring-boot-starter-webflux}, so {@link
 * com.rpgmaster.app.adapter.outbound.TeiRerankAdapter} would otherwise fail to
 * start with no such bean found. This defines it explicitly, with a longer
 * read timeout: cross-encoder reranking is CPU-bound inference over real
 * corpus-sized chunk text, and the library default (~10s) is too tight for that.
 */
@Configuration
public class RestClientConfig {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    @Scope(ConfigurableBeanFactory.SCOPE_PROTOTYPE)
    RestClient.Builder restClientBuilder() {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS.withReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(ClientHttpRequestFactories.get(settings));
    }
}
