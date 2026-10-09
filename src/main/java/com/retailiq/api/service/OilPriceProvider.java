package com.retailiq.api.service;

import java.time.LocalDate;
import java.util.Optional;

public interface OilPriceProvider {
    /** Return the newest known observation at or before the supplied cutoff date. */
    Optional<OilPriceObservation> latestOnOrBefore(LocalDate cutoffDate);
}
