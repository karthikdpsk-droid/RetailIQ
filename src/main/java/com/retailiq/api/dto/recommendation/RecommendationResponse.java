package com.retailiq.api.dto.recommendation;

import com.retailiq.api.entity.Recommendation;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record RecommendationResponse(Long id, Long storeId, Long productId,
                                     Recommendation.Type recommendationType,
                                     BigDecimal recommendedQuantity, String reason,
                                     LocalDate recommendationDate, Instant createdAt) { }
