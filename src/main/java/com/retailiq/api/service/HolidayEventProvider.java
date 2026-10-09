package com.retailiq.api.service;

import com.retailiq.api.entity.Store;
import java.time.LocalDate;
import java.util.Optional;

public interface HolidayEventProvider {
    Optional<Boolean> isHolidayEvent(Store store, LocalDate forecastDate);
}
