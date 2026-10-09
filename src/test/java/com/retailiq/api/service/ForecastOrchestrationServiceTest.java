package com.retailiq.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.retailiq.api.dto.forecast.StoreFamilyForecastCreateRequest;
import com.retailiq.api.entity.DemandUnit;
import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Store;
import com.retailiq.api.entity.StoreFamilyForecast;
import com.retailiq.api.ml.MlForecastClient;
import com.retailiq.api.ml.MlForecastRequest;
import com.retailiq.api.ml.MlForecastResponse;
import com.retailiq.api.repository.MlFamilyRepository;
import com.retailiq.api.repository.StoreFamilyForecastRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ForecastOrchestrationServiceTest {
    @Test void buildsCallsAndPersistsMonetaryStoreFamilyForecast() {
        StoreService storeService = mock(StoreService.class);
        MlFamilyRepository families = mock(MlFamilyRepository.class);
        ForecastFeatureService features = mock(ForecastFeatureService.class);
        MlForecastClient client = mock(MlForecastClient.class);
        StoreFamilyForecastRepository forecasts = mock(StoreFamilyForecastRepository.class);
        Store store = new Store(); store.setId(4L);
        MlFamily family = new MlFamily("FAMILY A"); family.setId(7L);
        LocalDate date = LocalDate.of(2026, 10, 9);
        StoreFamilyForecastCreateRequest command = new StoreFamilyForecastCreateRequest(4L, "FAMILY A", date);
        MlForecastRequest mlRequest = new MlForecastRequest(date, date.minusDays(1), 8, "FAMILY A", 2.0,
                "City", "State", "A", 6, 70.0, 0, 2026, 10, 9, 4, 41, 4, 0, 1,
                1.0, 2.0, 3.0, 4.0, 2.0, 2.5, 3.0, 0.5);
        when(storeService.accessibleStore(4L)).thenReturn(store);
        when(families.findByFamilyCode("FAMILY A")).thenReturn(Optional.of(family));
        when(features.build(store, family, date)).thenReturn(mlRequest);
        when(client.forecast(mlRequest, "req-1")).thenReturn(new MlForecastResponse(8, "FAMILY A", date,
                new BigDecimal("23.456789"), 1, "point_forecast", "demand_forecast_v1.0.2"));
        when(forecasts.saveAndFlush(any(StoreFamilyForecast.class))).thenAnswer(invocation -> {
            StoreFamilyForecast saved = invocation.getArgument(0); saved.setId(10L); return saved;
        });

        ForecastOrchestrationService orchestration = new ForecastOrchestrationService(
                storeService, families, features, client, forecasts);
        var response = orchestration.forecast(command, "req-1");

        assertThat(response.id()).isEqualTo(10L); assertThat(response.storeId()).isEqualTo(4L);
        assertThat(response.mlFamily()).isEqualTo("FAMILY A"); assertThat(response.forecastDate()).isEqualTo(date);
        assertThat(response.predictionCutoff()).isEqualTo(date.minusDays(1));
        assertThat(response.forecastDemand()).isEqualByComparingTo("23.4568");
        assertThat(response.demandUnit()).isEqualTo(DemandUnit.MONETARY_SALES);
        assertThat(response.modelVersion()).isEqualTo("demand_forecast_v1.0.2");
        ArgumentCaptor<StoreFamilyForecast> persisted = ArgumentCaptor.forClass(StoreFamilyForecast.class);
        verify(forecasts).saveAndFlush(persisted.capture());
        assertThat(persisted.getValue().getStore()).isSameAs(store);
        assertThat(persisted.getValue().getMlFamily()).isSameAs(family);
        assertThat(persisted.getValue().getDemandUnit()).isEqualTo(DemandUnit.MONETARY_SALES);
        assertThat(persisted.getValue().getForecastDemand()).isEqualByComparingTo(response.forecastDemand());
        verify(client).forecast(mlRequest, "req-1");
    }
}
