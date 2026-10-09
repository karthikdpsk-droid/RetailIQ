package com.retailiq.api.service;

import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import org.springframework.stereotype.Component;

/** Dated oil prices from the checked-in model reference data; lookup never selects a future date. */
@Component
public class CsvOilPriceProvider implements OilPriceProvider {
    private final TreeMap<LocalDate, Double> observations = new TreeMap<>();

    public CsvOilPriceProvider() {
        for (Map<String, String> row : CsvForecastSourceLoader.read("ml-data/oil.csv")) {
            String value = row.get("dcoilwtico");
            if (value == null || value.isBlank()) continue;
            double price = Double.parseDouble(value);
            if (!Double.isFinite(price)) throw new IllegalStateException("Oil source contains a non-finite price");
            observations.put(CsvForecastSourceLoader.date(row.get("date")), price);
        }
    }

    @Override
    public Optional<OilPriceObservation> latestOnOrBefore(LocalDate cutoffDate) {
        if (cutoffDate == null) return Optional.empty();
        Map.Entry<LocalDate, Double> entry = observations.floorEntry(cutoffDate);
        return entry == null ? Optional.empty()
                : Optional.of(new OilPriceObservation(entry.getKey(), entry.getValue()));
    }
}
