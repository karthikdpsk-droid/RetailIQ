package com.retailiq.api.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.retailiq.api.entity.Store;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class CsvForecastSourceProviderTest {
    private final CsvHolidayEventProvider holidays = new CsvHolidayEventProvider();
    private final CsvOilPriceProvider oil = new CsvOilPriceProvider();

    @Test
    void holidaySourceAppliesNationalRegionalAndLocalEventsAndExcludesTransferredRows() {
        Store store = store("Manta", "Manabi");
        assertThat(holidays.isHolidayEvent(store, LocalDate.of(2012, 3, 2))).contains(true); // Local Manta

        store.setCity("Quito"); store.setState("Cotopaxi");
        assertThat(holidays.isHolidayEvent(store, LocalDate.of(2012, 4, 1))).contains(true); // Regional Cotopaxi

        store.setCity("Any City"); store.setState("Any State");
        assertThat(holidays.isHolidayEvent(store, LocalDate.of(2017, 12, 25))).contains(true); // National

        store.setCity("Quito");
        assertThat(holidays.isHolidayEvent(store, LocalDate.of(2017, 12, 6))).contains(false); // transferred Local event
    }

    @Test
    void holidaySourceFailsClosedOutsideItsCoveredDates() {
        assertThat(holidays.isHolidayEvent(store("Quito", "Pichincha"), LocalDate.of(2026, 10, 8))).isEmpty();
    }

    @Test
    void oilLookupUsesNewestAvailableObservationAtOrBeforeCutoff() {
        LocalDate cutoff = LocalDate.of(2013, 1, 15);
        OilPriceObservation observation = oil.latestOnOrBefore(cutoff).orElseThrow();
        assertThat(observation.sourceDate()).isEqualTo(cutoff);
        assertThat(observation.sourceDate()).isBeforeOrEqualTo(cutoff);
        assertThat(observation.value()).isEqualTo(93.26);
        assertThat(oil.latestOnOrBefore(LocalDate.of(2013, 1, 16)).orElseThrow().sourceDate())
                .isEqualTo(LocalDate.of(2013, 1, 16));
        assertThat(oil.latestOnOrBefore(LocalDate.of(2013, 1, 1))).isEmpty();
    }

    private static Store store(String city, String state) {
        Store store = new Store(); store.setCity(city); store.setState(state); return store;
    }
}
