package com.ledgerline.anomaly;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Thresholds for the rule-based anomaly flags. */
@ConfigurationProperties("ledgerline.anomaly")
public record AnomalyProperties(
        @DefaultValue("5000000") long largeAmountPaise,
        @DefaultValue("3") int repeatedRefusalsCount,
        @DefaultValue("600") long repeatedRefusalsWindowSeconds,
        @DefaultValue("5") int rapidDebitsCount,
        @DefaultValue("60") long rapidDebitsWindowSeconds,
        @DefaultValue("1000000") long nearDrainMinAmountPaise,
        @DefaultValue("5") int nearDrainRemainingPercent) {
}
