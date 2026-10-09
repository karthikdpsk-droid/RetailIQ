package com.retailiq.api.service;

import com.retailiq.api.entity.Store;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Holiday applicability follows the ML feature pipeline: National, matching state, or matching city. */
@Component
public class CsvHolidayEventProvider implements HolidayEventProvider {
    private final List<Map<String, String>> events = CsvForecastSourceLoader.read("ml-data/holidays_events.csv");
    private final LocalDate firstDate = events.stream().map(row -> CsvForecastSourceLoader.date(row.get("date")))
            .min(LocalDate::compareTo).orElseThrow();
    private final LocalDate lastDate = events.stream().map(row -> CsvForecastSourceLoader.date(row.get("date")))
            .max(LocalDate::compareTo).orElseThrow();

    @Override
    public Optional<Boolean> isHolidayEvent(Store store, LocalDate forecastDate) {
        if (store == null || forecastDate == null || forecastDate.isBefore(firstDate) || forecastDate.isAfter(lastDate))
            return Optional.empty();
        boolean applies = events.stream().anyMatch(event ->
                forecastDate.equals(CsvForecastSourceLoader.date(event.get("date")))
                        && !Boolean.parseBoolean(event.get("transferred"))
                        && applies(event, store));
        return Optional.of(applies);
    }

    private static boolean applies(Map<String, String> event, Store store) {
        String locale = event.get("locale").trim();
        String name = event.get("locale_name").trim();
        return "National".equals(locale)
                || ("Regional".equals(locale) && sameLocation(name, store.getState()))
                || ("Local".equals(locale) && sameLocation(name, store.getCity()));
    }

    private static boolean sameLocation(String source, String storeLocation) {
        return storeLocation != null && source.equalsIgnoreCase(storeLocation.trim());
    }
}
