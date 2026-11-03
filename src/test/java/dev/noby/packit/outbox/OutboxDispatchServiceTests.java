package dev.noby.packit.outbox;

import dev.noby.packit.dto.PackageCreateRequest;
import dev.noby.packit.dto.PackageResponse;
import dev.noby.packit.service.PackageService;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;

@SpringBootTest(properties = {
        "packit.outbox.publisher.enabled=false",
        "packit.outbox.publisher.batch-size=2"
})
@Transactional
class OutboxDispatchServiceTests {

    @MockitoBean
    JwtDecoder jwtDecoder;

    @MockitoBean
    RabbitTemplate rabbitTemplate;

    @Autowired
    PackageService packageService;

    @Autowired
    OutboxDispatchService dispatchService;

    @Autowired
    JdbcTemplate jdbcTemplate;

    @Test
    void marksMessagePublishedOnlyAfterBrokerConfirmation() {
        doAnswer(call -> {
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        PackageResponse created = packageService.createPackage(
                new PackageCreateRequest("Confirmado", "CONFIRM-TEST", null));
        assertEquals(1, dispatchService.publishBatch());

        Message sent = captureSentMessage();
        assertEquals(MessageDeliveryMode.PERSISTENT, sent.getMessageProperties().getDeliveryMode());
        assertEquals("application/json", sent.getMessageProperties().getContentType());
        assertNotNull(sent.getMessageProperties().getMessageId());
        assertEquals(1, countPublished(created.id()));
    }

    @Test
    void keepsReturnedMessagePendingForRetry() {
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE",
                    call.getArgument(0), call.getArgument(1)));
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        PackageResponse created = packageService.createPackage(
                new PackageCreateRequest("Sin ruta", "NO-ROUTE-TEST", null));
        assertEquals(0, dispatchService.publishBatch());
        assertEquals(0, countPublished(created.id()));
        String packageId = created.id().toString();
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT attempt_count FROM outbox_events WHERE payload ->> 'packageId' = ?",
                Integer.class, packageId));
        String error = jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_events WHERE payload ->> 'packageId' = ?",
                String.class, packageId);
        assertTrue(error.contains("NO_ROUTE"));
    }

    @Test
    void retriesAfterBrokerBecomesAvailable() {
        AtomicInteger sends = new AtomicInteger();
        doAnswer(call -> {
            if (sends.incrementAndGet() == 1) {
                throw new AmqpException("broker unavailable");
            }
            CorrelationData correlation = call.getArgument(3);
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        PackageResponse created = packageService.createPackage(
                new PackageCreateRequest("Recuperado", "RETRY-TEST", null));
        assertEquals(0, dispatchService.publishBatch());
        assertEquals(0, countPublished(created.id()));
        assertEquals(1, dispatchService.publishBatch());
        assertEquals(1, countPublished(created.id()));
        assertEquals(2, sends.get());
        assertNull(jdbcTemplate.queryForObject(
                "SELECT last_error FROM outbox_events WHERE payload ->> 'packageId' = ?",
                String.class, created.id().toString()));
    }

    @Test
    void unroutableMessageDoesNotBlockNextMessage() {
        AtomicInteger sends = new AtomicInteger();
        doAnswer(call -> {
            Message message = call.getArgument(2);
            CorrelationData correlation = call.getArgument(3);
            if (sends.incrementAndGet() == 1) {
                correlation.setReturned(new ReturnedMessage(message, 312, "NO_ROUTE",
                        call.getArgument(0), call.getArgument(1)));
            }
            correlation.getFuture().complete(new CorrelationData.Confirm(true, null));
            return null;
        }).when(rabbitTemplate).send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));

        PackageResponse first = packageService.createPackage(
                new PackageCreateRequest("Primero", "FIRST-TEST", null));
        PackageResponse second = packageService.createPackage(
                new PackageCreateRequest("Segundo", "SECOND-TEST", null));

        assertEquals(1, dispatchService.publishBatch());
        assertEquals(0, countPublished(first.id()));
        assertEquals(1, countPublished(second.id()));
    }

    private Message captureSentMessage() {
        org.mockito.ArgumentCaptor<Message> captor = org.mockito.ArgumentCaptor.forClass(Message.class);
        verify(rabbitTemplate).send(anyString(), anyString(), captor.capture(), any(CorrelationData.class));
        return captor.getValue();
    }

    private int countPublished(Long packageId) {
        Integer published = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE payload ->> 'packageId' = ? "
                        + "AND published_at IS NOT NULL",
                Integer.class, packageId.toString());
        assertNotNull(published);
        return published;
    }
}
