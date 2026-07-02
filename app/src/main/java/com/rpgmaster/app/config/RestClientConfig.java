package com.rpgmaster.app.config;

import java.time.Duration;

import org.springframework.boot.web.client.ClientHttpRequestFactories;
import org.springframework.boot.web.client.ClientHttpRequestFactorySettings;
import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Cross-encoder reranking ({@link com.rpgmaster.app.adapter.outbound.TeiRerankAdapter})
 * is CPU-bound inference over real corpus-sized chunk text; the default 10s read
 * timeout is too tight for that, so all {@link org.springframework.web.client.RestClient}
 * beans get a longer one.
 */
@Configuration
public class RestClientConfig {

    private static final Duration READ_TIMEOUT = Duration.ofSeconds(60);

    @Bean
    RestClientCustomizer restClientTimeoutCustomizer() {
        var settings = ClientHttpRequestFactorySettings.DEFAULTS.withReadTimeout(READ_TIMEOUT);
        return builder -> builder.requestFactory(ClientHttpRequestFactories.get(settings));
    }
}
