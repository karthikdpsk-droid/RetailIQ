package com.retailiq.api.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailiq.api.service.StoreSalesDatasetImportService;
import java.nio.file.Path;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

@Component
public class StoreSalesDatasetImportRunner implements ApplicationRunner {
    private static final String ACTION = "retailiq.dataset-import";
    private final StoreSalesDatasetImportService importer;
    private final ObjectMapper json;

    public StoreSalesDatasetImportRunner(StoreSalesDatasetImportService importer, ObjectMapper json) {
        this.importer = importer;
        this.json = json;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        if (!args.containsOption(ACTION)) return;
        String action = option(args, ACTION);
        Path archive = Path.of(required(args, "retailiq.dataset-archive"));
        long ownerId = Long.parseLong(required(args, "retailiq.dataset-owner-id"));
        StoreSalesDatasetImportService.ImportReport report;
        if ("dry-run".equals(action)) {
            report = importer.preview(archive, ownerId);
        } else if ("apply".equals(action)) {
            report = importer.apply(archive, ownerId,
                    required(args, "retailiq.dataset-confirm-sha256"),
                    Boolean.parseBoolean(option(args, "retailiq.dataset-allow-production")));
        } else {
            throw new IllegalArgumentException("retailiq.dataset-import must be dry-run or apply");
        }
        System.out.println(json.writeValueAsString(report));
    }

    private static String required(ApplicationArguments args, String name) {
        String value = option(args, name);
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value;
    }

    private static String option(ApplicationArguments args, String name) {
        return args.getOptionValues(name) == null || args.getOptionValues(name).isEmpty()
                ? null : args.getOptionValues(name).get(0);
    }
}
