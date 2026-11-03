package dev.noby.packit;

import dev.noby.packit.dto.PackageCreateRequest;
import dev.noby.packit.dto.PackageItemRequest;
import dev.noby.packit.dto.PackageResponse;
import dev.noby.packit.dto.PackageUpdateRequest;
import dev.noby.packit.entity.PackageStatus;
import dev.noby.packit.service.PackageService;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

@SpringBootTest
class PackItApplicationTests {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    PackageService packageService;

    @Autowired
    EntityManager entityManager;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    PlatformTransactionManager transactionManager;

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

    @Test
    @Transactional
    void recordsHistoryAndDocumentJobsForPackageChanges() {
        PackageResponse created = packageService.createPackage(
                new PackageCreateRequest("Pedido original", "OUTBOX-TEST", null));
        Long itemId = packageService.addItem(created.id(),
                new PackageItemRequest("SKU-1", "Producto original", 2)).id();
        packageService.updatePackage(created.id(),
                new PackageUpdateRequest("Pedido actualizado", "OUTBOX-TEST", "Descripción"));
        packageService.updateItem(created.id(), itemId,
                new PackageItemRequest("SKU-2", "Producto actualizado", 3));
        packageService.changeStatus(created.id(), PackageStatus.PREPARING);
        packageService.changeStatus(created.id(), PackageStatus.READY);

        String packageId = created.id().toString();
        List<String> routes = jdbcTemplate.queryForList(
                "SELECT routing_key FROM outbox_events WHERE payload ->> 'packageId' = ? ORDER BY routing_key",
                String.class, packageId);
        assertEquals(List.of("document.dispatch", "document.intake", "package.created", "package.item.added",
                "package.item.updated", "package.status.changed", "package.status.changed", "package.updated"),
                routes);

        String intakeItem = jdbcTemplate.queryForObject(
                "SELECT payload #>> '{package,items,0,productName}' FROM outbox_events "
                        + "WHERE routing_key = 'document.intake' AND payload ->> 'packageId' = ?",
                String.class, packageId);
        assertEquals("Producto actualizado", intakeItem);

        String statusBefore = jdbcTemplate.queryForObject(
                "SELECT payload #>> '{before,status}' FROM outbox_events "
                        + "WHERE routing_key = 'package.status.changed' "
                        + "AND payload #>> '{after,status}' = 'PREPARING' AND payload ->> 'packageId' = ?",
                String.class, packageId);
        assertEquals("RECEIVED", statusBefore);

        Boolean validIds = jdbcTemplate.queryForObject(
                "SELECT bool_and(payload ->> 'eventId' = event_id::text "
                        + "AND (payload ->> 'schemaVersion')::int = 1) FROM outbox_events "
                        + "WHERE payload ->> 'packageId' = ?",
                Boolean.class, packageId);
        assertTrue(validIds);
    }

    @Test
    void rollsBackPackageAndOutboxTogether() {
        Long packageId = new TransactionTemplate(transactionManager).execute(status -> {
            PackageResponse created = packageService.createPackage(
                    new PackageCreateRequest("Pedido revertido", "OUTBOX-ROLLBACK", null));
            status.setRollbackOnly();
            return created.id();
        });

        assertFalse(packageService.getPackages(null).stream().anyMatch(p -> p.id().equals(packageId)));
        Integer messages = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE payload ->> 'packageId' = ?",
                Integer.class, packageId.toString());
        assertEquals(0, messages);
    }
}
