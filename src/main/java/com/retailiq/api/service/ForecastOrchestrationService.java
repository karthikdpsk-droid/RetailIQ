package com.retailiq.api.service;

import com.retailiq.api.dto.forecast.StoreFamilyForecastCreateRequest;
import com.retailiq.api.dto.forecast.StoreFamilyForecastResponse;
import com.retailiq.api.entity.DemandUnit;
import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Store;
import com.retailiq.api.entity.StoreFamilyForecast;
import com.retailiq.api.exception.ResourceNotFoundException;
import com.retailiq.api.ml.MlForecastClient;
import com.retailiq.api.ml.MlForecastRequest;
import com.retailiq.api.ml.MlForecastResponse;
import com.retailiq.api.repository.MlFamilyRepository;
import com.retailiq.api.repository.StoreFamilyForecastRepository;
import java.time.LocalDate;
import java.math.RoundingMode;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ForecastOrchestrationService {
    private static final Logger log = LoggerFactory.getLogger(ForecastOrchestrationService.class);
    private static final Pattern REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private final StoreService stores;
    private final MlFamilyRepository families;
    private final ForecastFeatureService features;
    private final MlForecastClient mlClient;
    private final StoreFamilyForecastRepository forecasts;

    public ForecastOrchestrationService(StoreService stores, MlFamilyRepository families,
            ForecastFeatureService features, MlForecastClient mlClient,
            StoreFamilyForecastRepository forecasts) {
        this.stores = stores; this.families = families; this.features = features;
        this.mlClient = mlClient; this.forecasts = forecasts;
    }

    @Transactional
    public StoreFamilyForecastResponse forecast(StoreFamilyForecastCreateRequest command, String requestId) {
        String safeRequestId = requestId != null && REQUEST_ID.matcher(requestId).matches()
                ? requestId : UUID.randomUUID().toString();
        long started = System.nanoTime();
        String familyCode = command.mlFamily() == null ? "" : command.mlFamily().trim();
        LocalDate date = command.forecastDate();
        try {
            log.info("forecast_started request_id={} store_id={} family={} forecast_date={}",
                    safeRequestId, command.storeId(), familyCode, date);
            Store store = stores.accessibleStore(command.storeId());
            MlFamily family = families.findByFamilyCode(familyCode)
                    .orElseThrow(() -> new ResourceNotFoundException("ML family not found"));
            MlForecastRequest mlRequest = features.build(store, family, date);
            MlForecastResponse prediction = mlClient.forecast(mlRequest, safeRequestId);

            StoreFamilyForecast saved = new StoreFamilyForecast();
            saved.setStore(store); saved.setMlFamily(family);
            saved.setForecastDate(date); saved.setPredictionCutoff(date.minusDays(1));
            // Match the database column scale so POST and subsequent GET return the same value.
            saved.setForecastDemand(prediction.forecastDemand().setScale(4, RoundingMode.HALF_UP));
            saved.setDemandUnit(DemandUnit.MONETARY_SALES);
            saved.setModelVersion(prediction.modelVersion());
            StoreFamilyForecast persisted = forecasts.saveAndFlush(saved);
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            log.info("forecast_completed request_id={} store_id={} family={} forecast_date={} model_version={} latency_ms={} outcome=SUCCESS",
                    safeRequestId, store.getId(), family.getFamilyCode(), date, prediction.modelVersion(), latencyMs);
            return new StoreFamilyForecastResponse(persisted.getId(), store.getId(), family.getFamilyCode(), date,
                    date.minusDays(1), persisted.getForecastDemand(), DemandUnit.MONETARY_SALES,
                    prediction.modelVersion(), persisted.getCreatedAt());
        } catch (RuntimeException ex) {
            long latencyMs = (System.nanoTime() - started) / 1_000_000;
            log.warn("forecast_completed request_id={} store_id={} family={} forecast_date={} latency_ms={} outcome=FAILURE error_type={}",
                    safeRequestId, command.storeId(), familyCode, date, latencyMs, ex.getClass().getSimpleName());
            throw ex;
        }
    }
}
