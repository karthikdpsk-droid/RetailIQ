package com.retailiq.api.config;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.ml.SupportedMlFamilies;
import com.retailiq.api.repository.MlFamilyRepository;
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

    private final MlFamilyRepository mlFamilies;

    public DataInitializer(MlFamilyRepository mlFamilies) {
        this.mlFamilies = mlFamilies;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        for (String familyCode : SupportedMlFamilies.CODES) {
            if (mlFamilies.findByFamilyCode(familyCode).isEmpty()) {
                mlFamilies.save(new MlFamily(familyCode));
                log.info("Initialized authoritative ML family code: {}", familyCode);
            }
        }
    }
}
