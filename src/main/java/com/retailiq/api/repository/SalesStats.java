package com.retailiq.api.repository;

import java.math.BigDecimal;

public record SalesStats(long records, BigDecimal monetaryTotal) {}
