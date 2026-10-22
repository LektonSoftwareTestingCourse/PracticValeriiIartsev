package com.processing.service;

import com.processing.config.RabbitMQConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class LoggerClientTest {
    @Mock
    private RabbitTemplate rabbitTemplate;
    private LoggerClient client;

    @BeforeEach
    void setUp() {
        client = new LoggerClient(rabbitTemplate);
    }

    @Test
    void shouldPublishOriginalTransactionWhenSendCommandCompletes() {
        // Arrange
        var transaction = SwitchTestFixtures.transaction();

        // Act
        var logged = client.log(transaction);

        // Assert
        assertThat(logged).isTrue();
        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
    }

    @Test
    void shouldReportFailureWhenPublishingThrowsAmqpException() {
        // Arrange
        var transaction = SwitchTestFixtures.transaction();
        doThrow(new AmqpException("broker unavailable")).when(rabbitTemplate)
                .convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);

        // Act
        var logged = client.log(transaction);

        // Assert
        assertThat(logged).isFalse();
        verify(rabbitTemplate).convertAndSend(RabbitMQConfig.EXCHANGE, RabbitMQConfig.ROUTING_KEY, transaction);
    }
}
