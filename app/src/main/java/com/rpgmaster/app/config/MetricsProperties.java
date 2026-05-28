package com.rpgmaster.app.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cost model used to derive the {@code rpg.query.cost_usd} counter.
 *
 * <p>Defaults are zero — local Ollama is free, so the counter stays at zero
 * but the meter still emits, which lets dashboards and tests exercise the
 * code path. Override per-profile when targeting paid providers (Bedrock
 * pricing in Phase 5, etc).
 *
 * @param cost token-pricing used to estimate cost per query
 */
@ConfigurationProperties(prefix = "rpg.metrics")
public record MetricsProperties(Cost cost) {

    public MetricsProperties {
        if (cost == null) {
            cost = new Cost(0.0d, 0.0d);
        }
    }

    /**
     * @param promptUsdPer1k     dollars per 1,000 prompt (input) tokens
     * @param completionUsdPer1k dollars per 1,000 completion (output) tokens
     */
    public record Cost(double promptUsdPer1k, double completionUsdPer1k) {
        public Cost {
            if (promptUsdPer1k < 0 || completionUsdPer1k < 0) {
                throw new IllegalArgumentException(
                        "rpg.metrics.cost prices must be non-negative");
            }
        }
    }
}
