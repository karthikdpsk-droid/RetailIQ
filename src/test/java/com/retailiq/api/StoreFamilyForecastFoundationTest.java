package com.retailiq.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.retailiq.api.dto.forecast.StoreFamilyForecastRequest;
import com.retailiq.api.entity.DemandUnit;
import com.retailiq.api.entity.MlFamily;
import com.retailiq.api.entity.Role;
import com.retailiq.api.entity.Store;
import com.retailiq.api.entity.StoreFamilyForecast;
import com.retailiq.api.entity.Product;
import com.retailiq.api.entity.Sale;
import com.retailiq.api.entity.User;
import com.retailiq.api.repository.MlFamilyRepository;
import com.retailiq.api.repository.StoreFamilyForecastRepository;
import com.retailiq.api.repository.StoreRepository;
import com.retailiq.api.repository.ProductRepository;
import com.retailiq.api.repository.SaleRepository;
import com.retailiq.api.repository.UserRepository;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class StoreFamilyForecastFoundationTest {
    @Autowired StoreRepository stores;
    @Autowired MlFamilyRepository families;
    @Autowired StoreFamilyForecastRepository forecasts;
    @Autowired UserRepository users;
    @Autowired ProductRepository products;
    @Autowired SaleRepository sales;

    @Test
    void explicitStoreMappingIsPersistedWithoutDerivingFromBackendIdOrCode() {
        Store store = store("S-001");
        assertThat(store.getMlStoreNbr()).isNull();
        assertThat(store.getMlCluster()).isNull();
        store.setMlStoreNbr(17);
        store.setMlCluster(4);
        Store saved = stores.saveAndFlush(store);
        assertThat(saved.getMlStoreNbr()).isEqualTo(17);
        assertThat(saved.getMlCluster()).isEqualTo(4);
        assertThat(stores.findByMlStoreNbr(17)).contains(saved);
    }

    @Test
    void duplicateMlStoreNumberIsRejected() {
        Store first = store("S-001"); first.setMlStoreNbr(17); stores.saveAndFlush(first);
        Store second = store("S-002"); second.setMlStoreNbr(17);
        assertThatThrownBy(() -> stores.saveAndFlush(second)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void explicitFamilyIsIndependentAndForecastDoesNotNeedProduct() {
        Store store = stores.saveAndFlush(store("S-001"));
        MlFamily family = families.saveAndFlush(new MlFamily("GROCERY I"));
        StoreFamilyForecast forecast = new StoreFamilyForecast();
        forecast.setStore(store);
        forecast.setMlFamily(family);
        forecast.setForecastDate(LocalDate.of(2026, 10, 8));
        forecast.setPredictionCutoff(LocalDate.of(2026, 10, 7));
        forecast.setForecastDemand(new BigDecimal("123.4500"));
        forecast.setDemandUnit(DemandUnit.MONETARY_SALES);
        forecast.setModelVersion("demand_forecast_v1.0.2");
        StoreFamilyForecast saved = forecasts.saveAndFlush(forecast);
        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getMlFamily().getFamilyCode()).isEqualTo("GROCERY I");
        assertThat(saved.getDemandUnit()).isEqualTo(DemandUnit.MONETARY_SALES);
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(forecasts.findByStoreIdAndMlFamilyFamilyCodeOrderByForecastDateDesc(store.getId(), "GROCERY I"))
                .hasSize(1);
    }

    @Test
    void duplicateForecastKeyIsRejectedButDifferentModelVersionIsAllowed() {
        Store store = stores.saveAndFlush(store("S-001"));
        MlFamily family = families.saveAndFlush(new MlFamily("GROCERY I"));
        forecasts.saveAndFlush(forecast(store, family, "v1"));
        forecasts.saveAndFlush(forecast(store, family, "v2"));
        assertThatThrownBy(() -> forecasts.saveAndFlush(forecast(store, family, "v1")))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void requestDtoBeanValidationRejectsMissingFamilyAndNegativeSales() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        StoreFamilyForecastRequest bad = new StoreFamilyForecastRequest(1L, " ",
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 7),
                new BigDecimal("-1"), DemandUnit.MONETARY_SALES, "v1");
        assertThat(validator.validate(bad)).hasSize(2);
        StoreFamilyForecastRequest inconsistent = new StoreFamilyForecastRequest(1L, "FAMILY",
                LocalDate.of(2026, 10, 8), LocalDate.of(2026, 10, 6),
                BigDecimal.ONE, DemandUnit.MONETARY_SALES, "v1");
        assertThat(validator.validate(inconsistent)).hasSize(1);
    }

    @Test
    void dailySalesAggregationUsesExplicitProductFamilyAndTotalAmountOnlyBeforeCutoff() {
        Store store = stores.saveAndFlush(store("S-001"));
        store.setMlStoreNbr(3);
        store = stores.saveAndFlush(store);
        MlFamily family = families.saveAndFlush(new MlFamily("EXPLICIT FAMILY"));
        MlFamily otherFamily = families.saveAndFlush(new MlFamily("OTHER FAMILY"));
        Product mapped = product(store, family, "MAPPED");
        Product other = product(store, otherFamily, "OTHER");
        LocalDate cutoff = LocalDate.of(2026, 10, 7);
        sale(store, mapped, cutoff.minusDays(2), new BigDecimal("12.50"));
        sale(store, mapped, cutoff.minusDays(2), new BigDecimal("2.50"));
        sale(store, other, cutoff.minusDays(2), new BigDecimal("500.00"));
        sale(store, mapped, cutoff.plusDays(1), new BigDecimal("900.00"));

        var results = sales.aggregateMonetarySalesByStoreFamilyAndDate(
                store.getId(), 3, family.getId(), cutoff.minusDays(28), cutoff);
        assertThat(results).containsExactly(new com.retailiq.api.repository.DailySalesTotal(
                cutoff.minusDays(2), new BigDecimal("15.00")));
    }

    private Store store(String code) {
        User owner = new User(); owner.setName("Owner"); owner.setEmail(code + "@example.com");
        owner.setPassword("hash"); owner.setRole(Role.SHOPKEEPER); users.saveAndFlush(owner);
        Store store = new Store(); store.setOwner(owner); store.setStoreCode(code);
        store.setName("Test"); store.setCity("Pune"); store.setState("MH");
        store.setAddress("Road"); store.setType("RETAIL");
        return store;
    }

    private StoreFamilyForecast forecast(Store store, MlFamily family, String version) {
        StoreFamilyForecast f = new StoreFamilyForecast(); f.setStore(store); f.setMlFamily(family);
        f.setForecastDate(LocalDate.of(2026, 10, 8)); f.setPredictionCutoff(LocalDate.of(2026, 10, 7));
        f.setForecastDemand(new BigDecimal("10")); f.setDemandUnit(DemandUnit.MONETARY_SALES);
        f.setModelVersion(version); return f;
    }

    private Product product(Store store, MlFamily family, String sku) {
        Product p = new Product(); p.setStore(store); p.setMlFamily(family); p.setName(sku);
        p.setSku(sku); p.setCategory("free-form category"); p.setUnitPrice(BigDecimal.ONE); p.setUnit("item");
        return products.saveAndFlush(p);
    }

    private void sale(Store store, Product product, LocalDate date, BigDecimal amount) {
        Sale sale = new Sale(); sale.setStore(store); sale.setProduct(product); sale.setQuantity(999);
        sale.setSaleDate(date); sale.setUnitPrice(BigDecimal.ONE); sale.setTotalAmount(amount);
        sales.saveAndFlush(sale);
    }
}
