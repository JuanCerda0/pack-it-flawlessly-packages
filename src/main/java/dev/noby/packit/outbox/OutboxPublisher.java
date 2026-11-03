package dev.noby.packit.outbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "packit.outbox.publisher.enabled", havingValue = "true", matchIfMissing = true)
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final OutboxDispatchService dispatchService;

    public OutboxPublisher(OutboxDispatchService dispatchService) {
        this.dispatchService = dispatchService;
    }

    @Scheduled(fixedDelayString = "${packit.outbox.publisher.delay-ms:5000}")
    public void publishPending() {
        int published = dispatchService.publishBatch();
        if (published > 0) {
            log.info("Published {} pending outbox messages", published);
        }
    }
}
