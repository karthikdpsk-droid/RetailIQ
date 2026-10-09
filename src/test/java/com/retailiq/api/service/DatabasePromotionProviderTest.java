package com.retailiq.api.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.PromotionPlan;
import com.retailiq.api.entity.Role;
import com.retailiq.api.entity.Store;
import com.retailiq.api.entity.User;
import com.retailiq.api.repository.MlFamilyRepository;
import com.retailiq.api.repository.PromotionPlanRepository;
import com.retailiq.api.repository.StoreRepository;
import com.retailiq.api.repository.UserRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.OptionalDouble;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class DatabasePromotionProviderTest {
    private static final LocalDate TARGET = LocalDate.of(2026, 10, 10);

    @Autowired PromotionPlanRepository plans;
    @Autowired PromotionPlanService planService;
    @Autowired DatabasePromotionProvider provider;
    @Autowired MlFamilyRepository families;
    @Autowired StoreRepository stores;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;

    private Store store;
    private MlFamily family;

    @BeforeEach
    void setUp() {
        User owner = users.saveAndFlush(new User("Promotion Owner", "promotion-owner@example.com",
                passwordEncoder.encode("SecurePass123"), Role.SHOPKEEPER));
        store = new Store(); store.setOwner(owner); store.setStoreCode("PROMO-1");
        store.setName("Promotion Store"); store.setCity("Quito"); store.setState("Pichincha");
        store.setAddress("Test"); store.setType("A"); store.setMlStoreNbr(3); store.setMlCluster(1);
        store = stores.saveAndFlush(store);
        family = families.saveAndFlush(new MlFamily("FAMILY A"));
    }

    @Test
    void validPlanIsReturnedForMatchingStoreFamilyAndDate() {
        addPlan(4, TARGET.atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(1));
        assertThat(provider.onPromotion(store, family, TARGET, cutoff())).hasValue(4.0);
    }

    @Test
    void planPublishedAfterTargetDateCutoffIsNotReturned() {
        addPlan(4, TARGET.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(1));
        assertThat(provider.onPromotion(store, family, TARGET, cutoff())).isEmpty();
    }

    @Test
    void missingPromotionReturnsEmpty() {
        assertThat(provider.onPromotion(store, family, TARGET, cutoff())).isEmpty();
    }

    @Test
    void explicitlyStoredZeroIsAValidPromotionValue() {
        addPlan(0, TARGET.atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThat(provider.onPromotion(store, family, TARGET, cutoff())).hasValue(0.0);
    }

    @Test
    void negativePromotionIsRejectedByPlanService() {
        assertThatThrownBy(() -> planService.createOrUpdate(3, family.getFamilyCode(), TARGET, -1, Instant.now()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void duplicateStoreFamilyDateIsRejectedByUniqueConstraint() {
        addPlan(1, TARGET.atStartOfDay(ZoneOffset.UTC).toInstant());
        PromotionPlan duplicate = plan(2, TARGET.atStartOfDay(ZoneOffset.UTC).toInstant());
        assertThatThrownBy(() -> plans.saveAndFlush(duplicate)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void updatingToLaterAvailabilityDoesNotExposePlanBeforeItsAvailability() {
        Instant later = TARGET.atStartOfDay(ZoneOffset.UTC).toInstant().plusSeconds(3600);
        planService.createOrUpdate(3, family.getFamilyCode(), TARGET, 5,
                TARGET.atStartOfDay(ZoneOffset.UTC).toInstant().minusSeconds(1));
        planService.createOrUpdate(3, family.getFamilyCode(), TARGET, 7, later);
        assertThat(provider.onPromotion(store, family, TARGET, cutoff())).isEmpty();
        assertThat(plans.findAvailable(3, family.getFamilyCode(), TARGET, later)).isPresent();
    }

    private void addPlan(int value, Instant availableFrom) { plans.saveAndFlush(plan(value, availableFrom)); }

    private Instant cutoff() { return TARGET.atStartOfDay(ZoneOffset.UTC).toInstant(); }

    private PromotionPlan plan(int value, Instant availableFrom) {
        PromotionPlan p = new PromotionPlan(); p.setMlStoreNbr(3); p.setMlFamily(family);
        p.setForecastDate(TARGET); p.setOnPromotion(value); p.setAvailableFrom(availableFrom); return p;
    }
}
