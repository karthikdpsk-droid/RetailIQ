package com.retailiq.api.dto.inventory;

import jakarta.validation.constraints.NotNull;

public record StockAdjustmentRequest(@NotNull(message = "Adjustment quantity is required") Integer adjustment) { }
