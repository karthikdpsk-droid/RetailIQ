package com.retailiq.api.service;

import java.time.LocalDate;

public record OilPriceObservation(LocalDate sourceDate, double value) {}
