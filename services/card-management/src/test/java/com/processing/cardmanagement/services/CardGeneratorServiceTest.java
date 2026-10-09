package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardsBatchGeneratedEvent;
import com.processing.cardmanagement.exceptions.CardGenerationLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardGeneratorOptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardGeneratorServiceTest {

    @Mock
    private CardService cardService;
    @Mock
    private CardEventNotifier eventNotifier;
    private CardGeneratorService service;

    @BeforeEach
    void setUp() {
        var options = new CardGeneratorOptions(
                new BigDecimal("1000000"),
                new BigDecimal("50000000"),
                new BigDecimal("5000000"),
                new BigDecimal("30000000"),
                "643",
                100
        );
        service = new CardGeneratorService(cardService, options, eventNotifier);
    }

    @DisplayName("C-11: размер пакета, распределение по BIN и свойства черновиков")
    @Test
    void shouldDistributeTwentyCardsEquallyWhenTwoBinsAreProvided() {
        // Arrange
        var bins = List.of("400000", "400001");
        List<Card> saved = List.of();
        when(cardService.createCards(anyList())).thenReturn(saved);

        // Act
        var result = service.generate(20, bins);

        // Assert
        assertThat(result).isSameAs(saved);
        var captor = ArgumentCaptor.forClass(List.class);
        verify(cardService).createCards(captor.capture());
        @SuppressWarnings("unchecked")
        List<CardDraft> drafts = captor.getValue();
        assertThat(drafts).hasSize(20);
        var counts = drafts.stream().collect(Collectors.groupingBy(CardDraft::bin, Collectors.counting()));
        assertThat(counts).containsExactlyInAnyOrderEntriesOf(Map.of("400000", 10L, "400001", 10L));
        assertThat(drafts).allSatisfy(draft -> {
            assertThat(draft.cardholderName()).isNotBlank();
            assertThat(draft.currencyCode()).isEqualTo("643");
            assertThat(draft.initialBalance()).isBetween(new BigDecimal("1000000"), new BigDecimal("49999999"));
            assertThat(draft.dailyLimit()).isBetween(new BigDecimal("5000000"), new BigDecimal("29999999"));
            assertThat(draft.monthlyLimit()).isEqualByComparingTo(draft.dailyLimit().multiply(new BigDecimal("30")));
        });
        var statuses = drafts.stream().collect(Collectors.groupingBy(CardDraft::status, Collectors.counting()));
        assertThat(statuses).containsExactlyInAnyOrderEntriesOf(Map.of(CardStatus.ACTIVE, 19L, CardStatus.BLOCKED, 1L));
        verify(eventNotifier).notifyListeners(new CardsBatchGeneratedEvent(statuses));
    }

    @ParameterizedTest
    @ValueSource(ints = {99, 100})
    void shouldGenerateWhenCountDoesNotExceedMaximum(int count) {
        // Arrange
        when(cardService.createCards(anyList())).thenReturn(List.of());

        // Act
        service.generate(count, List.of("400000"));

        // Assert
        var captor = ArgumentCaptor.forClass(List.class);
        verify(cardService).createCards(captor.capture());
        assertThat(captor.getValue()).hasSize(count);
    }

    @Test
    void shouldRejectWithoutSavingWhenCountExceedsMaximum() {
        // Arrange
        var bins = List.of("400000", "400001");

        // Act
        var error = catchThrowable(() -> service.generate(101, bins));

        // Assert
        assertThat(error).isInstanceOf(CardGenerationLimitException.class);
        verifyNoInteractions(cardService, eventNotifier);
    }
}
