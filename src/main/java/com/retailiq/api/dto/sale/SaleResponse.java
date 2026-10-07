package com.retailiq.api.dto.sale;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;

public record SaleResponse(
        Long id,
        Long storeId,
        Long productId,
        Integer quantity,
        LocalDate saleDate,
        BigDecimal unitPrice,
        BigDecimal totalAmount,
        Instant createdAt
) {}
