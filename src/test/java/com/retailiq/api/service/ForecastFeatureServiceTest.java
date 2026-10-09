package com.retailiq.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Store;
import com.retailiq.api.ml.MlForecastRequest;
import com.retailiq.api.repository.DailySalesTotal;
import com.retailiq.api.repository.SaleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import org.junit.jupiter.api.Test;

class ForecastFeatureServiceTest {
    private static final LocalDate DATE = LocalDate.of(2025, 1, 6); // Monday, ISO week 2
    private final SaleRepository sales = mock(SaleRepository.class);
    private final Promotions promotions = new Promotions();
    private final Holidays holidays = new Holidays();
    private final Oil oil = new Oil();
    private final ForecastFeatureService service = new ForecastFeatureService(sales, promotions, holidays, oil);

    @Test void derivesCalendarMappingAndContextFeatures() {
        when(sales.aggregateMonetarySalesByStoreFamilyAndDate(eq(44L), eq(3), eq(8L), any(), any())).thenReturn(List.of());
        MlForecastRequest request = service.build(store(), family(), DATE);
        assertThat(request.storeNbr()).isEqualTo(3); assertThat(request.family()).isEqualTo("FAMILY A");
        assertThat(request.forecastDate()).isEqualTo(DATE); assertThat(request.predictionCutoff()).isEqualTo(DATE.minusDays(1));
        assertThat(request.year()).isEqualTo(2025); assertThat(request.month()).isEqualTo(1); assertThat(request.day()).isEqualTo(6);
        assertThat(request.dayOfWeek()).isZero(); assertThat(request.weekOfYear()).isEqualTo(2);
        assertThat(request.quarter()).isEqualTo(1); assertThat(request.isWeekend()).isZero();
        assertThat(request.onpromotion()).isEqualTo(2.0); assertThat(request.hasPromotion()).isEqualTo(1);
        assertThat(request.isHolidayEvent()).isEqualTo(1); assertThat(request.dcoilwtico()).isEqualTo(70.0);
        assertThat(oil.requestedCutoff).isEqualTo(DATE.minusDays(2));
        assertThat(promotions.requestedCutoff).isEqualTo(DATE.atStartOfDay(java.time.ZoneOffset.UTC).toInstant().minusNanos(1));
    }

    @Test void computesExactLagsAndCalendarWindowStatisticsFromMonetaryTotals() {
        when(sales.aggregateMonetarySalesByStoreFamilyAndDate(eq(44L), eq(3), eq(8L), any(), any()))
                .thenReturn(dailyHistory(true));
        MlForecastRequest request = service.build(store(), family(), DATE);
        assertThat(request.salesLag1()).isEqualTo(2.0); assertThat(request.salesLag7()).isEqualTo(14.0);
        assertThat(request.salesLag14()).isEqualTo(28.0); assertThat(request.salesLag28()).isEqualTo(56.0);
        assertThat(request.salesRollingMean7()).isEqualTo(8.0); assertThat(request.salesRollingMean14()).isEqualTo(15.0);
        assertThat(request.salesRollingMean28()).isEqualTo(29.0);
        assertThat(request.salesRollingStd7()).isCloseTo(Math.sqrt(112.0 / 6.0), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test void skipsMissingDatesAndExcludesTargetAndFutureDates() {
        when(sales.aggregateMonetarySalesByStoreFamilyAndDate(eq(44L), eq(3), eq(8L), any(), any()))
                .thenReturn(List.of(point(DATE.minusDays(1), 10), point(DATE.minusDays(3), 30),
                        point(DATE, 999), point(DATE.plusDays(1), 1000)));
        MlForecastRequest request = service.build(store(), family(), DATE);
        assertThat(request.salesLag1()).isEqualTo(10.0); assertThat(request.salesLag7()).isNull();
        assertThat(request.salesRollingMean7()).isEqualTo(20.0);
        assertThat(request.salesRollingMean14()).isEqualTo(20.0); assertThat(request.salesRollingMean28()).isEqualTo(20.0);
        assertThat(request.salesRollingStd7()).isCloseTo(Math.sqrt(200.0), org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test void rollingStandardDeviationIsNullWhenOnlyOnePriorObservationExists() {
        when(sales.aggregateMonetarySalesByStoreFamilyAndDate(eq(44L), eq(3), eq(8L), any(), any()))
                .thenReturn(List.of(point(DATE.minusDays(2), 10)));
        assertThat(service.build(store(), family(), DATE).salesRollingStd7()).isNull();
    }

    @Test void emptyHistoryRemainsNullAndQueryStopsAtPredictionCutoff() {
        when(sales.aggregateMonetarySalesByStoreFamilyAndDate(eq(44L), eq(3), eq(8L), any(), any())).thenReturn(List.of());
        MlForecastRequest request = service.build(store(), family(), DATE);
        assertThat(request.salesLag1()).isNull(); assertThat(request.salesRollingMean7()).isNull();
        assertThat(request.salesRollingStd7()).isNull();
        org.mockito.Mockito.verify(sales).aggregateMonetarySalesByStoreFamilyAndDate(
                44L, 3, 8L, DATE.minusDays(28), DATE.minusDays(1));
    }

    @Test void missingStoreMappingClusterOrFamilyFailsClosed() {
        Store noMapping = store(); noMapping.setMlStoreNbr(null);
        assertThatThrownBy(() -> service.build(noMapping, family(), DATE))
                .isInstanceOf(ForecastMappingException.class)
                .hasMessage("Store ML store number mapping is required");
        Store noCluster = store(); noCluster.setMlCluster(null);
        assertThatThrownBy(() -> service.build(noCluster, family(), DATE))
                .isInstanceOf(ForecastMappingException.class)
                .hasMessage("Store ML cluster mapping is required");
        assertThatThrownBy(() -> service.build(store(), null, DATE)).isInstanceOf(ForecastMappingException.class);
    }

    @Test void rejectsOilObservationLaterThanTrainingCutoff() {
        oil.value = Optional.of(new OilPriceObservation(DATE.minusDays(1), 71));
        assertThatThrownBy(() -> service.build(store(), family(), DATE))
                .isInstanceOf(ForecastFeatureUnavailableException.class)
                .hasMessage("Oil price provider returned an invalid or future observation");
        assertThat(oil.requestedCutoff).isEqualTo(DATE.minusDays(2));
    }

    @Test void missingPromotionHolidayOrOilFailsClosed() {
        promotions.value = OptionalDouble.empty();
        assertThatThrownBy(() -> service.build(store(), family(), DATE)).isInstanceOf(ForecastFeatureUnavailableException.class);
        promotions.value = OptionalDouble.of(2); holidays.value = Optional.empty();
        assertThatThrownBy(() -> service.build(store(), family(), DATE)).isInstanceOf(ForecastFeatureUnavailableException.class);
        holidays.value = Optional.of(false); oil.value = Optional.empty();
        assertThatThrownBy(() -> service.build(store(), family(), DATE)).isInstanceOf(ForecastFeatureUnavailableException.class);
    }

    private static Store store() {
        Store s = new Store(); s.setId(44L); s.setMlStoreNbr(3); s.setMlCluster(5);
        s.setCity("Quito"); s.setState("Pichincha"); s.setType("A"); return s;
    }
    private static MlFamily family() { MlFamily f = new MlFamily("FAMILY A"); f.setId(8L); return f; }
    private static DailySalesTotal point(LocalDate date, double amount) { return new DailySalesTotal(date, BigDecimal.valueOf(amount)); }
    private static List<DailySalesTotal> dailyHistory(boolean targetAndFuture) {
        java.util.ArrayList<DailySalesTotal> rows = new java.util.ArrayList<>();
        for (int i = 1; i <= 28; i++) rows.add(point(DATE.minusDays(i), i * 2.0));
        if (targetAndFuture) { rows.add(point(DATE, 1000)); rows.add(point(DATE.plusDays(1), 2000)); }
        return rows;
    }
    private static final class Promotions implements PromotionProvider {
        OptionalDouble value = OptionalDouble.of(2);
        java.time.Instant requestedCutoff;
        public OptionalDouble onPromotion(Store s, MlFamily f, LocalDate d, java.time.Instant cutoff) { requestedCutoff = cutoff; return value; }
    }
    private static final class Holidays implements HolidayEventProvider {
        Optional<Boolean> value = Optional.of(true);
        public Optional<Boolean> isHolidayEvent(Store s, LocalDate d) { return value; }
    }
    private static final class Oil implements OilPriceProvider {
        Optional<OilPriceObservation> value = Optional.of(new OilPriceObservation(DATE.minusDays(2), 70));
        LocalDate requestedCutoff;
        public Optional<OilPriceObservation> latestOnOrBefore(LocalDate cutoff) { requestedCutoff = cutoff; return value; }
    }
}
