package com.retailiq.api.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.core.io.ClassPathResource;

/** Small CSV reader for the checked-in Favorita reference data used by model training. */
final class CsvForecastSourceLoader {
    private CsvForecastSourceLoader() {}

    static List<Map<String, String>> read(String resource) {
        try (InputStream input = new ClassPathResource(resource).getInputStream();
             BufferedReader reader = new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            String header = reader.readLine();
            if (header == null) throw new IllegalStateException("Empty forecast source: " + resource);
            List<String> columns = parse(header);
            List<Map<String, String>> rows = new ArrayList<>();
            for (String line; (line = reader.readLine()) != null;) {
                if (line.isBlank()) continue;
                List<String> values = parse(line);
                if (values.size() != columns.size())
                    throw new IllegalStateException("Malformed row in forecast source: " + resource);
                Map<String, String> row = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) row.put(columns.get(i), values.get(i));
                rows.add(Map.copyOf(row));
            }
            return List.copyOf(rows);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to load forecast source " + resource, ex);
        }
    }

    private static List<String> parse(String line) {
        List<String> fields = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    field.append('"'); i++;
                } else quoted = !quoted;
            } else if (c == ',' && !quoted) {
                fields.add(field.toString()); field.setLength(0);
            } else field.append(c);
        }
        fields.add(field.toString());
        return fields;
    }

    static LocalDate date(String value) { return LocalDate.parse(value); }
}
