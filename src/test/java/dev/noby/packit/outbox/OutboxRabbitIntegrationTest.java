package dev.noby.packit.outbox;

import dev.noby.packit.config.RabbitTopologyConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

@SpringBootTest(properties = {
        "packit.outbox.publisher.enabled=false",
        "packit.outbox.publisher.batch-size=1"
})
@EnabledIfSystemProperty(named = "packit.rabbit.integration", matches = "true")
@Transactional
class OutboxRabbitIntegrationTest {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Autowired
    OutboxDispatchService dispatchService;

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    JsonMapper jsonMapper;

    @Test
    void retriesUnroutableMessageThenPublishesWhenRouteExists() {
        UUID eventId = UUID.randomUUID();
        String suffix = eventId.toString();
        String queueName = "demo.outbox.integration." + suffix;
        String routingKey = "integration." + suffix;
        String payload = "{\"eventId\":\"" + eventId + "\",\"schemaVersion\":1}";
        jdbcTemplate.update(
                "INSERT INTO outbox_events (event_id, exchange_name, routing_key, payload, created_at) "
                        + "VALUES (?, ?, ?, CAST(? AS jsonb), ?)",
                eventId, RabbitTopologyConfig.HISTORY_EXCHANGE, "missing." + suffix,
                payload, Timestamp.from(Instant.now()));

        assertEquals(0, dispatchService.publishBatch());
        assertNull(publishedAt(eventId));

        Queue queue = QueueBuilder.durable(queueName)
                .withArgument("x-queue-type", "classic")
                .build();
        try {
            amqpAdmin.declareQueue(queue);
            amqpAdmin.declareBinding(BindingBuilder.bind(queue)
                    .to(new TopicExchange(RabbitTopologyConfig.HISTORY_EXCHANGE))
                    .with(routingKey));
            jdbcTemplate.update("UPDATE outbox_events SET routing_key = ? WHERE event_id = ?", routingKey, eventId);

            assertEquals(1, dispatchService.publishBatch());
            assertNotNull(publishedAt(eventId));
            Message received = rabbitTemplate.receive(queueName, 2000);
            assertNotNull(received);
            assertEquals(jsonMapper.readTree(payload),
                    jsonMapper.readTree(new String(received.getBody(), StandardCharsets.UTF_8)));
            assertEquals(eventId.toString(), received.getMessageProperties().getMessageId());
            assertEquals(2, jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM outbox_events WHERE event_id = ?", Integer.class, eventId));
        } finally {
            amqpAdmin.deleteQueue(queueName);
        }
    }

    private Timestamp publishedAt(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT published_at FROM outbox_events WHERE event_id = ?", Timestamp.class, eventId);
    }
}
