package com.retailiq.api.repository;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailySalesTotal(LocalDate saleDate, BigDecimal amount) {}
