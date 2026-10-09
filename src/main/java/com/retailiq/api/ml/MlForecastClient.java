package com.retailiq.api.ml;

import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

@Component
public class MlForecastClient {
    private final RestClient client;
    public MlForecastClient(RestClient mlRestClient) { this.client = mlRestClient; }

    public MlForecastResponse forecast(MlForecastRequest request, String suppliedRequestId) {
        String requestId = suppliedRequestId == null || suppliedRequestId.isBlank()
                ? UUID.randomUUID().toString() : suppliedRequestId;
        try {
            MlForecastResponse response = client.post().uri("/forecast")
                    .header("X-Request-ID", requestId)
                    .header(HttpHeaders.CONTENT_TYPE, "application/json")
                    .body(request).retrieve().body(MlForecastResponse.class);
            validateResponse(request, response);
            return response;
        } catch (RestClientResponseException ex) {
            throw new MlIntegrationException("ML service returned HTTP " + ex.getStatusCode().value(), 502, ex);
        } catch (ResourceAccessException ex) {
            throw new MlIntegrationException("ML service is unavailable", 503, ex);
        } catch (MlIntegrationException ex) {
            throw ex;
        } catch (RuntimeException ex) {
            throw new MlIntegrationException("ML service returned an invalid response", 502, ex);
        }
    }

    private void validateResponse(MlForecastRequest request, MlForecastResponse response) {
        if (response == null || !request.storeNbr().equals(response.storeNbr())
                || !request.family().equals(response.family())
                || !request.forecastDate().equals(response.forecastDate())
                || !Integer.valueOf(1).equals(response.forecastHorizonDays())
                || !"point_forecast".equals(response.forecastType())
                || response.forecastDemand() == null || response.forecastDemand().signum() < 0
                || response.modelVersion() == null || response.modelVersion().isBlank()) {
            throw new MlIntegrationException("ML service response failed contract validation", 502, null);
        }
        if (!Double.isFinite(response.forecastDemand().doubleValue()))
            throw new MlIntegrationException("ML service returned non-finite forecast demand", 502, null);
    }
}
