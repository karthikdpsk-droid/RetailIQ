package com.retailiq.api.service;

import java.util.Optional;
import java.util.OptionalDouble;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Fail-closed defaults. Each provider can be replaced independently with an authoritative source. */
@Configuration
public class ForecastProviderFallbackConfiguration {
    @Bean
    @ConditionalOnMissingBean(PromotionProvider.class)
    PromotionProvider unavailablePromotionProvider() { return (store, family, date, cutoff) -> OptionalDouble.empty(); }

    @Bean
    @ConditionalOnMissingBean(HolidayEventProvider.class)
    HolidayEventProvider unavailableHolidayEventProvider() { return (store, date) -> Optional.empty(); }

    @Bean
    @ConditionalOnMissingBean(OilPriceProvider.class)
    OilPriceProvider unavailableOilPriceProvider() { return cutoff -> Optional.empty(); }
}
