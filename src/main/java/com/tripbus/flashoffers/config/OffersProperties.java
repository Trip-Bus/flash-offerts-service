package com.tripbus.flashoffers.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "tripbus.offers")
public record OffersProperties(String seatInventoryUrl, String catalogUrl) {
}
