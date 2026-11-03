package dev.noby.packit.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class OutboxDispatchService {

    private static final Logger log = LoggerFactory.getLogger(OutboxDispatchService.class);

    private final JdbcTemplate jdbcTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final int batchSize;
    private final long confirmTimeoutMs;

    public OutboxDispatchService(JdbcTemplate jdbcTemplate, RabbitTemplate rabbitTemplate,
                                 @Value("${packit.outbox.publisher.batch-size:20}") int batchSize,
                                 @Value("${packit.outbox.publisher.confirm-timeout-ms:5000}") long confirmTimeoutMs) {
        this.jdbcTemplate = jdbcTemplate;
        this.rabbitTemplate = rabbitTemplate;
        this.batchSize = batchSize;
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Transactional
    public int publishBatch() {
        List<PendingMessage> pending = jdbcTemplate.query(
                "SELECT event_id, exchange_name, routing_key, payload::text AS payload "
                        + "FROM outbox_events WHERE published_at IS NULL "
                        + "ORDER BY attempt_count, created_at, event_id LIMIT ? FOR UPDATE SKIP LOCKED",
                (rs, row) -> new PendingMessage((UUID) rs.getObject("event_id"),
                        rs.getString("exchange_name"), rs.getString("routing_key"), rs.getString("payload")),
                batchSize);

        int published = 0;
        for (PendingMessage message : pending) {
            try {
                publishAndConfirm(message);
            } catch (Exception error) {
                if (error instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                String reason = error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage();
                jdbcTemplate.update(
                        "UPDATE outbox_events SET attempt_count = attempt_count + 1, last_error = ? "
                                + "WHERE event_id = ?",
                        reason, message.eventId());
                log.warn("Outbox message {} remains pending: {}", message.eventId(), reason);
                if (!(error instanceof UnroutableMessageException)) {
                    break;
                }
                continue;
            }
            jdbcTemplate.update(
                    "UPDATE outbox_events SET published_at = clock_timestamp(), "
                            + "attempt_count = attempt_count + 1, last_error = NULL WHERE event_id = ?",
                    message.eventId());
            published++;
        }
        return published;
    }

    private void publishAndConfirm(PendingMessage pending) throws Exception {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(pending.eventId().toString());
        Message message = new Message(pending.payload().getBytes(StandardCharsets.UTF_8), properties);
        CorrelationData correlation = new CorrelationData(pending.eventId().toString());

        rabbitTemplate.send(pending.exchange(), pending.routingKey(), message, correlation);
        CorrelationData.Confirm confirm = correlation.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
        if (!confirm.isAck()) {
            throw new IllegalStateException("RabbitMQ rejected publication: " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new UnroutableMessageException("RabbitMQ could not route message: "
                    + correlation.getReturned().getReplyText());
        }
    }

    private static class UnroutableMessageException extends IllegalStateException {
        private UnroutableMessageException(String message) {
            super(message);
        }
    }

    private record PendingMessage(UUID eventId, String exchange, String routingKey, String payload) {
    }
}
