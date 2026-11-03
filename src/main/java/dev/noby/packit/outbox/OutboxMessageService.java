package dev.noby.packit.outbox;

import dev.noby.packit.entity.PackageEntity;
import dev.noby.packit.entity.PackageItemEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class OutboxMessageService {

    private static final String HISTORY_EXCHANGE = "packit.package.events";
    private static final String DOCUMENT_EXCHANGE = "packit.document.commands";

    private final JdbcTemplate jdbcTemplate;
    private final JsonMapper jsonMapper;

    public OutboxMessageService(JdbcTemplate jdbcTemplate, JsonMapper jsonMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonMapper = jsonMapper;
    }

    public enum HistoryType {
        PACKAGE_CREATED("package.created"),
        PACKAGE_UPDATED("package.updated"),
        PACKAGE_STATUS_CHANGED("package.status.changed"),
        PACKAGE_ITEM_ADDED("package.item.added"),
        PACKAGE_ITEM_UPDATED("package.item.updated");

        private final String routingKey;

        HistoryType(String routingKey) {
            this.routingKey = routingKey;
        }
    }

    public enum DocumentType {
        INTAKE("document.intake"),
        DISPATCH("document.dispatch");

        private final String routingKey;

        DocumentType(String routingKey) {
            this.routingKey = routingKey;
        }
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordHistory(PackageEntity packageEntity, HistoryType type, Object before, Object after) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> message = envelope(eventId, packageEntity.getId(), now);
        message.put("type", type.name());
        message.put("actorId", actorId());
        message.put("before", before);
        message.put("after", after);
        insert(eventId, HISTORY_EXCHANGE, type.routingKey, message, now);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordDocument(PackageEntity packageEntity, DocumentType type) {
        UUID eventId = UUID.randomUUID();
        Instant now = Instant.now();
        Map<String, Object> message = envelope(eventId, packageEntity.getId(), now);
        message.put("documentType", type.name());
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("name", packageEntity.getName());
        snapshot.put("reference", packageEntity.getReference());
        snapshot.put("status", packageEntity.getStatus().name());
        snapshot.put("items", packageEntity.getItems().stream().map(this::itemSnapshot).toList());
        message.put("package", snapshot);
        insert(eventId, DOCUMENT_EXCHANGE, type.routingKey, message, now);
    }

    public Map<String, Object> packageDetails(PackageEntity packageEntity) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("name", packageEntity.getName());
        details.put("reference", packageEntity.getReference());
        details.put("description", packageEntity.getDescription());
        return details;
    }

    public Map<String, Object> itemDetails(PackageItemEntity item) {
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("itemId", item.getId());
        details.put("sku", item.getSku());
        details.put("productName", item.getProductName());
        details.put("quantity", item.getQuantity());
        return details;
    }

    private Map<String, Object> itemSnapshot(PackageItemEntity item) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sku", item.getSku());
        snapshot.put("productName", item.getProductName());
        snapshot.put("quantity", item.getQuantity());
        return snapshot;
    }

    private Map<String, Object> envelope(UUID eventId, Long packageId, Instant occurredAt) {
        Map<String, Object> message = new LinkedHashMap<>();
        message.put("eventId", eventId.toString());
        message.put("schemaVersion", 1);
        message.put("occurredAt", occurredAt.toString());
        message.put("packageId", packageId);
        return message;
    }

    private String actorId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication instanceof JwtAuthenticationToken jwt ? jwt.getToken().getSubject() : null;
    }

    private void insert(UUID eventId, String exchange, String routingKey, Map<String, Object> message, Instant now) {
        jdbcTemplate.update(
                "INSERT INTO outbox_events (event_id, exchange_name, routing_key, payload, created_at) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                eventId, exchange, routingKey, jsonMapper.writeValueAsString(message), Timestamp.from(now));
    }
}
