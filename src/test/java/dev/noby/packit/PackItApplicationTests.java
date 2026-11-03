package dev.noby.packit;

import dev.noby.packit.dto.PackageCreateRequest;
import dev.noby.packit.dto.PackageItemRequest;
import dev.noby.packit.dto.PackageResponse;
import dev.noby.packit.entity.PackageStatus;
import dev.noby.packit.service.PackageService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class PackItApplicationTests {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    PackageService packageService;

    @Autowired
    EntityManager entityManager;

    @Test
    void contextLoads() {
    }

    @Test
    @Transactional
    void persistsPackageItemsAndStatusInPostgres() {
        PackageResponse created = packageService.createPackage(
                new PackageCreateRequest("Paquete de prueba", "TEST-PG", null));
        packageService.addItem(created.id(), new PackageItemRequest("SKU-TEST", "Producto de prueba", 2));
        packageService.changeStatus(created.id(), PackageStatus.PREPARING);

        entityManager.flush();
        entityManager.clear();

        PackageResponse reloaded = packageService.getPackage(created.id());
        assertEquals(PackageStatus.PREPARING, reloaded.status());
        assertEquals(1, reloaded.items().size());
        assertEquals("SKU-TEST", reloaded.items().getFirst().sku());
        assertEquals(2, reloaded.items().getFirst().quantity());
    }
}
