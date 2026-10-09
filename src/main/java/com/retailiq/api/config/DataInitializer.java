package com.retailiq.api.config;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.repository.MlFamilyRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Initializes the authoritative Favorita ML target family schema values
 * when starting with an empty database. Disabled during unit testing via profile.
 */
@Component
@Profile("!test")
public class DataInitializer implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private static final List<String> OFFICIAL_MODEL_FAMILIES = List.of(
        "AUTOMOTIVE", "BABY CARE", "BEVERAGES", "BOOKS", "BREAD/BAKERY",
        "CLEANING", "DAIRY", "DELI", "EGGS", "FROZEN FOODS",
        "GROCERY I", "GROCERY II", "HARDWARE", "HOME AND KITCHEN I",
        "HOME AND KITCHEN II", "HOME APPLIANCES", "HOME CARE", "LADIESWEAR",
        "LAWN AND GARDEN", "LINGERIE", "LIQUOR,WINE,BEER", "MAGAZINES",
        "MEATS", "PERSONAL CARE", "PET SUPPLIES", "POULTRY",
        "PREPARED FOODS", "PRODUCE", "SCHOOL AND OFFICE SUPPLIES", "SEAFOOD",
        "FAMILY A", "FAMILY B", "GROCERY"
    );

    private final MlFamilyRepository mlFamilies;

    public DataInitializer(MlFamilyRepository mlFamilies) {
        this.mlFamilies = mlFamilies;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String familyCode : OFFICIAL_MODEL_FAMILIES) {
            if (mlFamilies.findByFamilyCode(familyCode).isEmpty()) {
                mlFamilies.save(new MlFamily(familyCode));
                log.info("Initialized authoritative ML family code: {}", familyCode);
            }
        }
    }
}
