package com.retailiq.api.controller;

import com.retailiq.api.dto.dashboard.DashboardSummaryResponse;
import com.retailiq.api.service.DashboardSummaryService;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/dashboard")
@Tag(name = "Dashboard")
@SecurityRequirement(name = "bearerAuth")
public class DashboardSummaryController {
    private final DashboardSummaryService service;

    public DashboardSummaryController(DashboardSummaryService service) {
        this.service = service;
    }

    @GetMapping("/summary")
    public DashboardSummaryResponse getSummary() {
        return service.get();
    }
}
