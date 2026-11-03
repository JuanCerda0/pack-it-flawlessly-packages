package dev.noby.packit.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

@Configuration
@EnableScheduling
public class RabbitTopologyConfig {

    private static final Logger log = LoggerFactory.getLogger(RabbitTopologyConfig.class);

    public static final String HISTORY_EXCHANGE = "packit.package.events";
    public static final String DOCUMENT_EXCHANGE = "packit.document.commands";
    public static final String DEAD_LETTER_EXCHANGE = "packit.dead.letters";

    @Bean
    Declarables packitTopology() {
        TopicExchange historyExchange = new TopicExchange(HISTORY_EXCHANGE, true, false);
        DirectExchange documentExchange = new DirectExchange(DOCUMENT_EXCHANGE, true, false);
        DirectExchange deadLetterExchange = new DirectExchange(DEAD_LETTER_EXCHANGE, true, false);

        Queue history = workQueue("history.package");
        Queue intake = workQueue("documents.intake");
        Queue dispatch = workQueue("documents.dispatch");
        Queue historyDlq = deadLetterQueue("history.package.dlq");
        Queue intakeDlq = deadLetterQueue("documents.intake.dlq");
        Queue dispatchDlq = deadLetterQueue("documents.dispatch.dlq");

        return new Declarables(List.of(
                historyExchange, documentExchange, deadLetterExchange,
                history, intake, dispatch, historyDlq, intakeDlq, dispatchDlq,
                BindingBuilder.bind(history).to(historyExchange).with("package.#"),
                BindingBuilder.bind(intake).to(documentExchange).with("document.intake"),
                BindingBuilder.bind(dispatch).to(documentExchange).with("document.dispatch"),
                BindingBuilder.bind(historyDlq).to(deadLetterExchange).with(historyDlq.getName()),
                BindingBuilder.bind(intakeDlq).to(deadLetterExchange).with(intakeDlq.getName()),
                BindingBuilder.bind(dispatchDlq).to(deadLetterExchange).with(dispatchDlq.getName())
        ));
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory) {
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMandatory(true);
        template.setReturnsCallback(returned -> log.warn("RabbitMQ returned unroutable message {}: {}",
                returned.getMessage().getMessageProperties().getMessageId(), returned.getReplyText()));
        return template;
    }

    private Queue workQueue(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-queue-type", "classic")
                .deadLetterExchange(DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(name + ".dlq")
                .build();
    }

    private Queue deadLetterQueue(String name) {
        return QueueBuilder.durable(name)
                .withArgument("x-queue-type", "classic")
                .build();
    }
}
