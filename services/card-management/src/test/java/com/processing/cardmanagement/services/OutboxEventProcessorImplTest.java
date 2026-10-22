package com.processing.cardmanagement.services;

import com.processing.cardmanagement.config.RabbitMQConfig;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.models.CardOutboxEventData;
import com.processing.cardmanagement.models.OutboxEventDataStatus;
import com.processing.cardmanagement.options.OutboxOptions;
import com.processing.cardmanagement.repositories.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Answers;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxEventProcessorImplTest {
    private static final Instant CREATED_AT = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant PROCESSED_AT = Instant.parse("2026-09-24T10:00:01Z");
    private static final CardServicePatchEvent EVENT = new CardServicePatchEvent("4000001234567899");

    @Mock
    private OutboxRepository repository;
    @Mock
    private RabbitTemplate rabbitTemplate;
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private MockedStatic<Instant> clock;
    private OutboxEventProcessorImpl processor;

    @BeforeEach
    void setUp() {
        clock.when(Instant::now).thenReturn(PROCESSED_AT);
        processor = new OutboxEventProcessorImpl(repository, rabbitTemplate, new OutboxOptions(1000, 3));
    }

    @DisplayName("N-06: третья ошибка переводит событие в FAILED; первые две оставляют PENDING")
    @ParameterizedTest
    @CsvSource({"0, 1, PENDING", "1, 2, PENDING", "2, 3, FAILED"})
    void shouldSaveRetryStateWhenPublishingFails(int retries, int expectedRetries, OutboxEventDataStatus status) {
        // Arrange
        var event = pendingEvent(retries);
        doThrow(new AmqpException("broker unavailable")).when(rabbitTemplate).convertAndSend(
                RabbitMQConfig.CARD_EVENTS_EXCHANGE, "card.CardServicePatchEvent", EVENT);

        // Act
        processor.processSingleEvent(event);

        // Assert
        var captor = ArgumentCaptor.forClass(CardOutboxEventData.class);
        var order = inOrder(rabbitTemplate, repository);
        order.verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.CARD_EVENTS_EXCHANGE, "card.CardServicePatchEvent", EVENT);
        order.verify(repository).save(captor.capture());
        assertThat(captor.getValue().retryCount()).isEqualTo(expectedRetries);
        assertThat(captor.getValue().status()).isEqualTo(status);
        assertThat(captor.getValue().lastError()).isEqualTo("broker unavailable");
        assertThat(captor.getValue().processedAt()).isNull();
        assertThat(captor.getValue().id()).isEqualTo(event.id());
        assertThat(captor.getValue().event()).isEqualTo(EVENT);
        assertThat(event.retryCount()).isEqualTo(retries);
    }

    @Test
    void shouldSaveProcessedStateWhenPublishingSucceeds() {
        // Arrange
        var event = pendingEvent(1);

        // Act
        processor.processSingleEvent(event);

        // Assert
        var captor = ArgumentCaptor.forClass(CardOutboxEventData.class);
        verify(repository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(OutboxEventDataStatus.PROCESSED);
        assertThat(captor.getValue().processedAt()).isEqualTo(PROCESSED_AT);
        assertThat(captor.getValue().retryCount()).isEqualTo(1);
        verify(rabbitTemplate).convertAndSend(
                RabbitMQConfig.CARD_EVENTS_EXCHANGE, "card.CardServicePatchEvent", EVENT);
    }

    private CardOutboxEventData pendingEvent(int retries) {
        return new CardOutboxEventData(UUID.fromString("00000000-0000-0000-0000-000000000001"), EVENT,
                CREATED_AT, null, retries, null, OutboxEventDataStatus.PENDING);
    }
}
