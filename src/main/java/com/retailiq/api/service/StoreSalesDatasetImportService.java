package com.retailiq.api.service;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Store;
import com.retailiq.api.entity.User;
import com.retailiq.api.exception.ResourceNotFoundException;
import com.retailiq.api.repository.FamilySalesObservationRepository;
import com.retailiq.api.repository.MlFamilyRepository;
import com.retailiq.api.repository.StoreRepository;
import com.retailiq.api.repository.StoreTransactionMetricRepository;
import com.retailiq.api.repository.UserRepository;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import javax.sql.DataSource;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Validates the Favorita source archive before writing. No products or inventory are synthesized because
 * the source contains family-level monetary sales, not a product catalogue or stock quantities.
 */
@Service
public class StoreSalesDatasetImportService {
    private static final String TRAIN = "train.csv";
    private static final String TEST = "test.csv";
    private static final String TRANSACTIONS = "transactions.csv";
    private static final int MAX_FAMILIES = 64;
    private static final long MAX_DUPLICATE_INDEX_BITS = 100_000_000L;
    private static final int BATCH_SIZE = 1_000;
    private static final String ADDRESS_NOT_PROVIDED = "Address not supplied by imported dataset";
    private static final CSVFormat CSV_FORMAT = CSVFormat.RFC4180.builder()
            .setHeader().setSkipHeaderRecord(true).build();

    private static final String MERGE_FAMILY_SQL = """
            MERGE INTO family_sales_observations AS target
            USING (VALUES (?, ?, ?, ?, ?, ?)) AS incoming
                (store_id, ml_family_id, observation_date, sales, on_promotion, source_file)
            ON target.store_id = incoming.store_id
                AND target.ml_family_id = incoming.ml_family_id
                AND target.observation_date = incoming.observation_date
            WHEN MATCHED THEN UPDATE SET sales = incoming.sales,
                on_promotion = incoming.on_promotion, source_file = incoming.source_file
            WHEN NOT MATCHED THEN INSERT
                (store_id, ml_family_id, observation_date, sales, on_promotion, source_file)
                VALUES (incoming.store_id, incoming.ml_family_id, incoming.observation_date,
                    incoming.sales, incoming.on_promotion, incoming.source_file)
            """;
    private static final String MERGE_TRANSACTIONS_SQL = """
            MERGE INTO store_transaction_metrics AS target
            USING (VALUES (?, ?, ?, ?)) AS incoming (store_id, metric_date, transactions, source_file)
            ON target.store_id = incoming.store_id
                AND target.metric_date = incoming.metric_date
                AND target.source_file = incoming.source_file
            WHEN MATCHED THEN UPDATE SET transactions = incoming.transactions
            WHEN NOT MATCHED THEN INSERT (store_id, metric_date, transactions, source_file)
                VALUES (incoming.store_id, incoming.metric_date, incoming.transactions, incoming.source_file)
            """;

    private final StoreRepository stores;
    private final UserRepository users;
    private final MlFamilyRepository families;
    private final FamilySalesObservationRepository observations;
    private final StoreTransactionMetricRepository transactionMetrics;
    private final JdbcTemplate jdbc;
    private final DataSource dataSource;

    public StoreSalesDatasetImportService(StoreRepository stores, UserRepository users,
            MlFamilyRepository families, FamilySalesObservationRepository observations,
            StoreTransactionMetricRepository transactionMetrics, JdbcTemplate jdbc, DataSource dataSource) {
        this.stores = stores;
        this.users = users;
        this.families = families;
        this.observations = observations;
        this.transactionMetrics = transactionMetrics;
        this.jdbc = jdbc;
        this.dataSource = dataSource;
    }

    @Transactional(readOnly = true)
    public ImportReport preview(Path archive, Long ownerId) {
        Plan plan = validate(archive, ownerId);
        return report(plan, false, 0);
    }

    @Transactional
    public ImportReport apply(Path archive, Long ownerId, String confirmedSha256, boolean allowProduction) {
        Plan plan = validate(archive, ownerId);
        if (confirmedSha256 == null || !plan.sha256.equalsIgnoreCase(confirmedSha256.trim()))
            throw new IllegalArgumentException("Apply requires the SHA-256 from the reviewed dry-run report");
        if (isProductionDatabase() && !allowProduction)
            throw new IllegalStateException("Production import is blocked without the explicit production-approval flag");

        User owner = users.findById(ownerId)
                .orElseThrow(() -> new ResourceNotFoundException("Import owner not found"));
        Map<Integer, Store> storeByNumber = resolveStores(plan, owner, true);
        Map<String, MlFamily> familyByCode = families.findAll().stream()
                .collect(java.util.stream.Collectors.toMap(MlFamily::getFamilyCode, family -> family));

        importObservations(plan, storeByNumber, familyByCode, TRAIN);
        importObservations(plan, storeByNumber, familyByCode, TEST);
        importTransactions(plan, storeByNumber);
        return report(plan, true, plan.counts.storesToInsert);
    }

    private Plan validate(Path archive, Long ownerId) {
        if (archive == null || !Files.isRegularFile(archive) || !Files.isReadable(archive))
            throw new IllegalArgumentException("Dataset ZIP must be an existing readable file");
        if (ownerId == null || ownerId < 1)
            throw new IllegalArgumentException("An existing owner ID is required for store ownership mapping");
        User owner = users.findById(ownerId).orElseThrow(() -> new ResourceNotFoundException("Import owner not found"));
        String sha256 = sha256(archive);
        Counts counts = new Counts();
        Map<Integer, StoreSource> sourceStores = readStores(archive, counts);
        if (sourceStores.isEmpty()) counts.error("stores.csv contains no store records");

        ObservationContext context = new ObservationContext(sourceStores);
        Set<String> supportedFamilies = families.findAll().stream()
                .map(MlFamily::getFamilyCode).collect(java.util.stream.Collectors.toSet());
        scanObservations(archive, TRAIN, true, context, supportedFamilies, counts);
        scanObservations(archive, TEST, false, context, supportedFamilies, counts);
        scanTransactions(archive, context, counts);
        if (context.familyCodes.size() > MAX_FAMILIES)
            counts.error("Dataset contains more than " + MAX_FAMILIES + " distinct family codes");
        else {
            validateObservationDuplicates(archive, context, counts);
            validateTransactionDuplicates(archive, context, counts);
        }

        Map<Integer, Store> storeByNumber = resolveStores(new Plan(archive, ownerId, owner, sha256,
                sourceStores, context, counts), owner, false);
        long existingTrain = observations.countByStore_Owner_IdAndSourceFile(ownerId, TRAIN);
        long existingTest = observations.countByStore_Owner_IdAndSourceFile(ownerId, TEST);
        long existingTransactions = transactionMetrics.countByStore_Owner_IdAndSourceFile(ownerId, TRANSACTIONS);
        counts.storesToInsert = sourceStores.keySet().stream()
                .filter(number -> !storeByNumber.containsKey(number)).count();
        counts.storesUnchanged = sourceStores.size() - counts.storesToInsert;
        counts.observationsToInsert = Math.max(0, context.trainRows - existingTrain)
                + Math.max(0, context.testRows - existingTest);
        counts.observationsToUpdate = Math.min(context.trainRows, existingTrain)
                + Math.min(context.testRows, existingTest);
        counts.transactionsToInsert = Math.max(0, context.transactionRows - existingTransactions);
        counts.transactionsToUpdate = Math.min(context.transactionRows, existingTransactions);
        if (counts.errors > 0) throw new DatasetValidationException(counts.validationSummary());
        return new Plan(archive, ownerId, owner, sha256, sourceStores, context, counts);
    }

    private Map<Integer, StoreSource> readStores(Path archive, Counts counts) {
        Map<Integer, StoreSource> result = new LinkedHashMap<>();
        try (ZipFile zip = openZip(archive); CSVParser parser = parse(zip, "stores.csv")) {
            requireHeaders(parser, Set.of("store_nbr", "city", "state", "type", "cluster"), "stores.csv");
            for (CSVRecord row : parser) {
                try {
                    int number = positiveInt(row.get("store_nbr"));
                    int cluster = positiveInt(row.get("cluster"));
                    StoreSource store = new StoreSource(number, requiredText(row.get("city")),
                            requiredText(row.get("state")), requiredText(row.get("type")), cluster);
                    if (result.putIfAbsent(number, store) != null)
                        counts.error("stores.csv row " + row.getRecordNumber() + " duplicates a store number");
                    counts.storeRows++;
                } catch (IllegalArgumentException ex) {
                    counts.error("stores.csv row " + row.getRecordNumber() + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            counts.error("stores.csv could not be parsed");
        }
        return result;
    }

    private void scanObservations(Path archive, String file, boolean hasSales,
            ObservationContext context, Set<String> supportedFamilies, Counts counts) {
        Set<String> headers = hasSales
                ? Set.of("date", "store_nbr", "family", "sales", "onpromotion")
                : Set.of("date", "store_nbr", "family", "onpromotion");
        try (ZipFile zip = openZip(archive); CSVParser parser = parse(zip, file)) {
            requireHeaders(parser, headers, file);
            for (CSVRecord row : parser) {
                try {
                    LocalDate date = LocalDate.parse(requiredText(row.get("date")));
                    int storeNumber = positiveInt(row.get("store_nbr"));
                    String family = requiredText(row.get("family"));
                    BigDecimal promotion = nonNegativeDecimal(row.get("onpromotion"));
                    if (!context.stores.containsKey(storeNumber))
                        throw new IllegalArgumentException("store number is absent from stores.csv");
                    if (!supportedFamilies.contains(family))
                        throw new IllegalArgumentException("family code is not in the initialized ML family reference data");
                    if (hasSales) nonNegativeDecimal(row.get("sales"));
                    context.familyCodes.add(family);
                    context.observeDate(date);
                    if (hasSales) context.trainRows++; else context.testRows++;
                    counts.observationRows++;
                } catch (IllegalArgumentException ex) {
                    counts.error(file + " row " + row.getRecordNumber() + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            counts.error(file + " could not be parsed");
        }
    }

    private void scanTransactions(Path archive, ObservationContext context, Counts counts) {
        try (ZipFile zip = openZip(archive); CSVParser parser = parse(zip, TRANSACTIONS)) {
            requireHeaders(parser, Set.of("date", "store_nbr", "transactions"), TRANSACTIONS);
            for (CSVRecord row : parser) {
                try {
                    LocalDate date = LocalDate.parse(requiredText(row.get("date")));
                    int storeNumber = positiveInt(row.get("store_nbr"));
                    int transactions = nonNegativeInt(row.get("transactions"));
                    if (!context.stores.containsKey(storeNumber))
                        throw new IllegalArgumentException("store number is absent from stores.csv");
                    context.observeDate(date);
                    context.transactionRows++;
                    counts.transactionRows++;
                } catch (IllegalArgumentException ex) {
                    counts.error(TRANSACTIONS + " row " + row.getRecordNumber() + ": " + ex.getMessage());
                }
            }
        } catch (IOException ex) {
            counts.error(TRANSACTIONS + " could not be parsed");
        }
    }

    private void validateObservationDuplicates(Path archive, ObservationContext context, Counts counts) {
        if (context.minDate == null || context.maxDate == null) return;
        long days = ChronoUnit.DAYS.between(context.minDate, context.maxDate) + 1;
        long bits = days * context.stores.size() * Math.max(context.familyCodes.size(), 1);
        if (bits > MAX_DUPLICATE_INDEX_BITS) {
            counts.error("Date/store/family key space is too large for bounded duplicate validation");
            return;
        }
        BitSet seen = new BitSet((int) bits);
        Map<String, Integer> familyIndex = indexed(context.familyCodes);
        for (String file : List.of(TRAIN, TEST)) {
            try (ZipFile zip = openZip(archive); CSVParser parser = parse(zip, file)) {
                for (CSVRecord row : parser) {
                    try {
                        LocalDate date = LocalDate.parse(row.get("date"));
                        int storeNumber = Integer.parseInt(row.get("store_nbr"));
                        String family = row.get("family").trim();
                        Integer s = context.storeIndex.get(storeNumber);
                        Integer f = familyIndex.get(family);
                        if (s == null || f == null) continue;
                        long index = (ChronoUnit.DAYS.between(context.minDate, date) * context.stores.size()
                                + s) * context.familyCodes.size() + f;
                        if (seen.get((int) index))
                            counts.error(file + " row " + row.getRecordNumber() + " duplicates a date/store/family key");
                        else seen.set((int) index);
                    } catch (IllegalArgumentException ex) {
                        // The validation pass above reports malformed rows with their source location.
                    }
                }
            } catch (IOException ex) {
                counts.error(file + " could not be parsed during duplicate validation");
            }
        }
    }

    private void validateTransactionDuplicates(Path archive, ObservationContext context, Counts counts) {
        if (context.minDate == null || context.maxDate == null) return;
        long days = ChronoUnit.DAYS.between(context.minDate, context.maxDate) + 1;
        long bits = days * context.stores.size();
        if (bits > MAX_DUPLICATE_INDEX_BITS) {
            counts.error("Date/store transaction key space is too large for bounded duplicate validation");
            return;
        }
        BitSet seen = new BitSet((int) bits);
        try (ZipFile zip = openZip(archive); CSVParser parser = parse(zip, TRANSACTIONS)) {
            for (CSVRecord row : parser) {
                try {
                    LocalDate date = LocalDate.parse(row.get("date"));
                    int storeNumber = Integer.parseInt(row.get("store_nbr"));
                    Integer storeIndex = context.storeIndex.get(storeNumber);
                    if (storeIndex == null) continue;
                    int index = (int) (ChronoUnit.DAYS.between(context.minDate, date) * context.stores.size()
                            + storeIndex);
                    if (seen.get(index))
                        counts.error(TRANSACTIONS + " row " + row.getRecordNumber() + " duplicates a date/store key");
                    else seen.set(index);
                } catch (IllegalArgumentException ex) {
                    // The validation pass above reports malformed rows with their source location.
                }
            }
        } catch (IOException ex) {
            counts.error(TRANSACTIONS + " could not be parsed during duplicate validation");
        }
    }

    private Map<Integer, Store> resolveStores(Plan plan, User owner, boolean createMissing) {
        Map<Integer, Store> resolved = new HashMap<>();
        for (StoreSource source : plan.sourceStores.values()) {
            Store existing = stores.findByMlStoreNbr(source.number).orElse(null);
            if (existing != null) {
                if (!existing.getOwner().getId().equals(owner.getId()))
                    throw new IllegalArgumentException("Store ownership conflict for source store number " + source.number);
                resolved.put(source.number, existing);
                continue;
            }
            String storeCode = String.format(java.util.Locale.ROOT, "FAV-%02d", source.number);
            if (stores.existsByStoreCodeIgnoreCase(storeCode))
                throw new IllegalArgumentException("Store code conflict for source store number " + source.number);
            if (createMissing) {
                Store store = new Store();
                store.setOwner(owner);
                store.setStoreCode(storeCode);
                store.setName("Favorita Store " + source.number);
                store.setCity(source.city);
                store.setState(source.state);
                store.setAddress(ADDRESS_NOT_PROVIDED);
                store.setType(source.type);
                store.setActive(true);
                store.setMlStoreNbr(source.number);
                store.setMlCluster(source.cluster);
                resolved.put(source.number, stores.save(store));
            }
        }
        if (createMissing) stores.flush();
        return resolved;
    }

    private void importObservations(Plan plan, Map<Integer, Store> storeByNumber,
            Map<String, MlFamily> familyByCode, String file) {
        boolean hasSales = TRAIN.equals(file);
        try (ZipFile zip = openZip(plan.archive); CSVParser parser = parse(zip, file)) {
            BatchCollector<ObservationRow> batch = new BatchCollector<>(BATCH_SIZE,
                    rows -> jdbc.batchUpdate(MERGE_FAMILY_SQL, rows, rows.size(), this::setObservationParameters));
            for (CSVRecord row : parser) {
                Integer storeNumber = Integer.valueOf(row.get("store_nbr"));
                String familyCode = row.get("family").trim();
                batch.add(new ObservationRow(storeByNumber.get(storeNumber).getId(),
                        familyByCode.get(familyCode).getId(), LocalDate.parse(row.get("date")),
                        hasSales ? new BigDecimal(row.get("sales")) : null,
                        new BigDecimal(row.get("onpromotion")), file));
            }
            batch.flush();
        } catch (IOException ex) {
            throw new IllegalStateException(file + " could not be read while applying the import", ex);
        }
    }

    private void importTransactions(Plan plan, Map<Integer, Store> storeByNumber) {
        try (ZipFile zip = openZip(plan.archive); CSVParser parser = parse(zip, TRANSACTIONS)) {
            BatchCollector<TransactionRow> batch = new BatchCollector<>(BATCH_SIZE,
                    rows -> jdbc.batchUpdate(MERGE_TRANSACTIONS_SQL, rows, rows.size(), this::setTransactionParameters));
            for (CSVRecord row : parser) {
                int storeNumber = Integer.parseInt(row.get("store_nbr"));
                batch.add(new TransactionRow(storeByNumber.get(storeNumber).getId(),
                        LocalDate.parse(row.get("date")), Integer.parseInt(row.get("transactions")), TRANSACTIONS));
            }
            batch.flush();
        } catch (IOException ex) {
            throw new IllegalStateException(TRANSACTIONS + " could not be read while applying the import", ex);
        }
    }

    private void setObservationParameters(PreparedStatement statement, ObservationRow row) throws SQLException {
        statement.setLong(1, row.storeId);
        statement.setLong(2, row.familyId);
        statement.setDate(3, Date.valueOf(row.date));
        if (row.sales == null) statement.setNull(4, Types.NUMERIC); else statement.setBigDecimal(4, row.sales);
        statement.setBigDecimal(5, row.onPromotion);
        statement.setString(6, row.sourceFile);
    }

    private void setTransactionParameters(PreparedStatement statement, TransactionRow row) throws SQLException {
        statement.setLong(1, row.storeId);
        statement.setDate(2, Date.valueOf(row.date));
        statement.setInt(3, row.transactions);
        statement.setString(4, row.sourceFile);
    }

    private ImportReport report(Plan plan, boolean applied, long storesInserted) {
        Counts c = plan.counts;
        return new ImportReport(plan.sha256, plan.owner.getId(), applied, c.storeRows,
                plan.context.trainRows, plan.context.testRows, plan.context.transactionRows, storesInserted,
                c.storesUnchanged,
                c.observationsToInsert, c.observationsToUpdate, c.transactionsToInsert,
                c.transactionsToUpdate, c.invalidRows, 0, plan.context.minDate, plan.context.maxDate,
                List.of("Source contains no individual product catalogue or current stock quantities.",
                        "Imported store address is marked as not supplied by the source.",
                        "Forecast demand remains monetary; it is not physical stock quantity."));
    }

    private boolean isProductionDatabase() {
        try (Connection connection = dataSource.getConnection()) {
            String catalog = connection.getCatalog();
            String url = connection.getMetaData().getURL();
            return "retailiq_vjsl".equalsIgnoreCase(catalog)
                    || (url != null && url.contains("onrender.com"));
        } catch (SQLException ex) {
            throw new IllegalStateException("Could not verify the target database before import", ex);
        }
    }

    private static String sha256(Path archive) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(archive);
                 DigestInputStream stream = new DigestInputStream(input, digest)) {
                stream.transferTo(java.io.OutputStream.nullOutputStream());
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (IOException ex) {
            throw new IllegalArgumentException("Dataset ZIP could not be read", ex);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static ZipFile openZip(Path archive) throws IOException {
        return new ZipFile(archive.toFile(), StandardCharsets.UTF_8);
    }

    private static CSVParser parse(ZipFile zip, String name) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null || entry.isDirectory()) throw new IOException("Required dataset file is missing: " + name);
        Reader reader = new InputStreamReader(zip.getInputStream(entry), StandardCharsets.UTF_8);
        return new CSVParser(reader, CSV_FORMAT);
    }

    private static void requireHeaders(CSVParser parser, Set<String> required, String file) throws IOException {
        if (!parser.getHeaderMap().keySet().containsAll(required))
            throw new IOException(file + " is missing required columns");
    }

    private static String requiredText(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("required value is missing");
        return value.trim();
    }

    private static int positiveInt(String value) {
        int number = Integer.parseInt(requiredText(value));
        if (number < 1) throw new IllegalArgumentException("value must be positive");
        return number;
    }

    private static int nonNegativeInt(String value) {
        int number = Integer.parseInt(requiredText(value));
        if (number < 0) throw new IllegalArgumentException("value must not be negative");
        return number;
    }

    private static BigDecimal nonNegativeDecimal(String value) {
        BigDecimal number = new BigDecimal(requiredText(value));
        if (number.signum() < 0) throw new IllegalArgumentException("value must not be negative");
        return number;
    }

    private static Map<String, Integer> indexed(Set<String> values) {
        Map<String, Integer> result = new HashMap<>();
        int index = 0;
        for (String value : values) result.put(value, index++);
        return result;
    }

    public record ImportReport(String archiveSha256, Long ownerId, boolean applied,
            long storeRows, long trainingRows, long testRows, long transactionRows,
            long storesInserted, long storesUnchanged, long observationsInserted,
            long observationsUpdated, long transactionsInserted, long transactionsUpdated,
            long invalidRows, long skippedRows, LocalDate firstObservationDate, LocalDate lastObservationDate,
            List<String> notes) {}

    private record StoreSource(int number, String city, String state, String type, int cluster) {}
    private record ObservationRow(Long storeId, Long familyId, LocalDate date, BigDecimal sales,
            BigDecimal onPromotion, String sourceFile) {}
    private record TransactionRow(Long storeId, LocalDate date, int transactions, String sourceFile) {}
    private record Plan(Path archive, Long ownerId, User owner, String sha256,
            Map<Integer, StoreSource> sourceStores, ObservationContext context, Counts counts) {}

    private static final class ObservationContext {
        private final Map<Integer, StoreSource> stores;
        private final Map<Integer, Integer> storeIndex = new HashMap<>();
        private final Set<String> familyCodes = new LinkedHashSet<>();
        private LocalDate minDate;
        private LocalDate maxDate;
        private long trainRows;
        private long testRows;
        private long transactionRows;

        private ObservationContext(Map<Integer, StoreSource> stores) {
            this.stores = stores;
            int index = 0;
            for (int number : stores.keySet()) storeIndex.put(number, index++);
        }

        private void observeDate(LocalDate date) {
            if (minDate == null || date.isBefore(minDate)) minDate = date;
            if (maxDate == null || date.isAfter(maxDate)) maxDate = date;
        }
    }

    private static final class Counts {
        private long storeRows;
        private long observationRows;
        private long trainRows;
        private long testRows;
        private long transactionRows;
        private long invalidRows;
        private long errors;
        private long storesToInsert;
        private long storesUnchanged;
        private long observationsToInsert;
        private long observationsToUpdate;
        private long transactionsToInsert;
        private long transactionsToUpdate;
        private final List<String> examples = new ArrayList<>();

        private void error(String message) {
            errors++;
            invalidRows++;
            if (examples.size() < 20) examples.add(message);
        }

        private String validationSummary() {
            return "Dataset validation failed (" + errors + " invalid rows/checks): " + String.join("; ", examples);
        }
    }

    private static final class BatchCollector<T> {
        private final int maxSize;
        private final List<T> rows = new ArrayList<>();
        private final BatchWriter<T> writer;

        private BatchCollector(int maxSize, BatchWriter<T> writer) {
            this.maxSize = maxSize;
            this.writer = writer;
        }

        private void add(T row) {
            rows.add(row);
            if (rows.size() >= maxSize) flush();
        }

        private void flush() {
            if (rows.isEmpty()) return;
            writer.write(List.copyOf(rows));
            rows.clear();
        }
    }

    @FunctionalInterface
    private interface BatchWriter<T> {
        void write(List<T> rows);
    }

    public static final class DatasetValidationException extends IllegalArgumentException {
        public DatasetValidationException(String message) { super(message); }
    }
}
