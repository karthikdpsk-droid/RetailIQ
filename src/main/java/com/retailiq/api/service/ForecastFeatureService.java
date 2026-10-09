package com.retailiq.api.service;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Store;
import com.retailiq.api.ml.MlForecastRequest;
import com.retailiq.api.repository.DailySalesTotal;
import com.retailiq.api.repository.SaleRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.WeekFields;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.OptionalDouble;
import org.springframework.stereotype.Service;

@Service
public class ForecastFeatureService {
    private final SaleRepository sales;
    private final PromotionProvider promotions;
    private final HolidayEventProvider holidays;
    private final OilPriceProvider oilPrices;

    public ForecastFeatureService(SaleRepository sales, PromotionProvider promotions,
            HolidayEventProvider holidays, OilPriceProvider oilPrices) {
        this.sales = sales; this.promotions = promotions; this.holidays = holidays; this.oilPrices = oilPrices;
    }

    public MlForecastRequest build(Store store, MlFamily family, LocalDate forecastDate) {
        if (store == null || store.getId() == null || store.getId() < 1)
            throw new ForecastMappingException("Store is required");
        Integer mlStoreNbr = store.getMlStoreNbr();
        if (mlStoreNbr == null || mlStoreNbr < 1)
            throw new ForecastMappingException("Store ML store number mapping is required");
        Integer mlCluster = store.getMlCluster();
        if (mlCluster == null || mlCluster < 1)
            throw new ForecastMappingException("Store ML cluster mapping is required");
        if (family == null || family.getId() == null || family.getId() < 1
                || family.getFamilyCode() == null || family.getFamilyCode().isBlank())
            throw new ForecastMappingException("An explicit ML family is required");
        if (forecastDate == null) throw new ForecastMappingException("Forecast date is required");
        requireText(store.getCity(), "Store city");
        requireText(store.getState(), "Store state");
        requireText(store.getType(), "Store type");

        LocalDate predictionCutoff = forecastDate.minusDays(1);
        java.time.Instant cutoffInstant = predictionCutoff.plusDays(1).atStartOfDay(ZoneOffset.UTC)
                .minusNanos(1).toInstant();
        double promotion = promotions.onPromotion(store, family, forecastDate, cutoffInstant)
                .orElseThrow(() -> new ForecastFeatureUnavailableException("Promotion data is unavailable"));
        if (!Double.isFinite(promotion) || promotion < 0)
            throw new ForecastFeatureUnavailableException("Promotion data is invalid");
        boolean holiday = holidays.isHolidayEvent(store, forecastDate)
                .orElseThrow(() -> new ForecastFeatureUnavailableException("Holiday event data is unavailable"));

        LocalDate oilCutoff = forecastDate.minusDays(2);
        OilPriceObservation oil = oilPrices.latestOnOrBefore(oilCutoff)
                .orElseThrow(() -> new ForecastFeatureUnavailableException("Oil price data is unavailable"));
        if (oil.sourceDate() == null || oil.sourceDate().isAfter(oilCutoff) || !Double.isFinite(oil.value()))
            throw new ForecastFeatureUnavailableException("Oil price provider returned an invalid or future observation");

        LocalDate historyStart = forecastDate.minusDays(28);
        List<DailySalesTotal> historyRows = sales.aggregateMonetarySalesByStoreFamilyAndDate(
                store.getId(), mlStoreNbr, family.getId(), historyStart, predictionCutoff);
        Map<LocalDate, Double> history = new HashMap<>();
        for (DailySalesTotal row : historyRows) {
            if (row.saleDate() == null || row.saleDate().isAfter(predictionCutoff)) continue;
            if (row.amount() == null) continue;
            double amount = row.amount().doubleValue();
            if (!Double.isFinite(amount) || amount < 0)
                throw new ForecastFeatureUnavailableException("Monetary sales history contains an invalid value");
            history.put(row.saleDate(), amount);
        }
        LocalDate d = forecastDate;
        int dayOfWeek = d.getDayOfWeek().getValue() - 1; // Python weekday(): Monday=0
        return new MlForecastRequest(d, predictionCutoff, mlStoreNbr, family.getFamilyCode().trim(),
                promotion, store.getCity(), store.getState(), store.getType(), mlCluster, oil.value(),
                holiday ? 1 : 0, d.getYear(), d.getMonthValue(), d.getDayOfMonth(), dayOfWeek,
                d.get(WeekFields.ISO.weekOfWeekBasedYear()), (d.getMonthValue() - 1) / 3 + 1,
                dayOfWeek >= 5 ? 1 : 0, promotion > 0 ? 1 : 0,
                exact(history, d.minusDays(1)), exact(history, d.minusDays(7)),
                exact(history, d.minusDays(14)), exact(history, d.minusDays(28)),
                mean(history, d.minusDays(7), predictionCutoff),
                mean(history, d.minusDays(14), predictionCutoff),
                mean(history, d.minusDays(28), predictionCutoff),
                sampleStdDev(history, d.minusDays(7), predictionCutoff));
    }

    private static Double exact(Map<LocalDate, Double> history, LocalDate date) { return history.get(date); }

    private static Double mean(Map<LocalDate, Double> history, LocalDate start, LocalDate end) {
        double sum = 0; int n = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Double value = history.get(date);
            if (value != null) { sum += value; n++; }
        }
        return n == 0 ? null : sum / n;
    }

    private static Double sampleStdDev(Map<LocalDate, Double> history, LocalDate start, LocalDate end) {
        int n = 0; double mean = 0; double m2 = 0;
        for (LocalDate date = start; !date.isAfter(end); date = date.plusDays(1)) {
            Double value = history.get(date);
            if (value != null) { n++; double delta = value - mean; mean += delta / n; m2 += delta * (value - mean); }
        }
        return n < 2 ? null : Math.sqrt(m2 / (n - 1));
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new ForecastMappingException(field + " is missing");
    }
}
