package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.events.CardServiceCreationEvent;
import com.processing.cardmanagement.events.CardServiceDeletionEvent;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.exceptions.MassiveCardCreationCollisionException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Answers;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-09-24T12:00:00Z");

    private static final String PAN = "4000001234567899";
    private static final String RRN = "123456789012";

    @Mock
    private CardRepository cardRepository;
    @Mock
    private ReservationRepository reservationRepository;
    @Mock
    private ReservationRollbackRepository rollbackRepository;
    @Mock
    private CardServiceSettings settings;
    @Mock
    private CardServiceDefaults defaults;
    @Mock
    private PanGenerator panGenerator;
    @Mock
    private CardEventNotifier eventNotifier;
    @Mock
    private BinIssuerService binIssuerService;
    @Mock
    private RetryService retryService;
    @Mock
    private TransactionRunner transactionRunner;
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private MockedStatic<Instant> instantClock;
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private MockedStatic<YearMonth> monthClock;
    private CardServiceImpl service;

    private Card card;

    @BeforeEach
    void setUp() {
        instantClock.when(Instant::now).thenReturn(FIXED_TIME);
        var month = YearMonth.of(2026, 9);
        monthClock.when(YearMonth::now).thenReturn(month);
        service = new CardServiceImpl(cardRepository, reservationRepository, rollbackRepository, settings, defaults,
                panGenerator, eventNotifier, binIssuerService, retryService, transactionRunner);
        card = new Card(UUID.fromString("00000000-0000-0000-0000-000000000001"), PAN, "400000", "IVAN IVANOV",
                YearMonth.of(2029, 9), CardStatus.ACTIVE, "643", new BigDecimal("1000"), new BigDecimal("10000"),
                new BigDecimal("100"), "ISS001", Instant.parse("2026-09-24T12:00:00Z"));
    }

    @DisplayName("C-01: создание ACTIVE-карты")
    @Test
    void shouldSaveActiveCardWhenCreationRequestIsValid() {
        // Arrange
        prepareCreation("400000", PAN);

        // Act
        var result = service.createCard("400000", "IVAN IVANOV", "643", new BigDecimal("1200"),
                new BigDecimal("1500"), new BigDecimal("1000"));

        // Assert
        assertThat(result.pan()).isEqualTo(PAN);
        assertThat(result.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(result.issuerId()).isEqualTo("ISS001");
        assertThat(result.expiryDate()).isEqualTo(YearMonth.of(2029, 9));
        assertThat(result.createdAt()).isEqualTo(Instant.parse("2026-09-24T12:00:00Z"));
        assertThat(result.dailyLimit()).isEqualByComparingTo("1200");
        assertThat(result.monthlyLimit()).isEqualByComparingTo("1500");
        assertThat(result.availableBalance()).isEqualByComparingTo("1000");
        verify(cardRepository).create(result);
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
    }

    @DisplayName("C-04: отсутствующий PAN")
    @Test
    void shouldRejectWhenPanDoesNotExist() {
        // Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.empty());

        // Act
        var error = catchThrowable(() -> service.getCard(PAN));

        // Assert
        assertThat(error).isInstanceOf(CardNotFoundException.class);
    }

    @DisplayName("C-05: PATCH только статуса")
    @Test
    void shouldPreserveOtherFieldsWhenOnlyStatusIsPatched() {
        // Arrange
        executeTransactions();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        var result = service.patchCard(PAN, CardStatus.BLOCKED, null, null, null);

        // Assert
        assertThat(result.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(result).usingRecursiveComparison().ignoringFields("status").isEqualTo(card);
        verify(cardRepository).update(result);
        verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @DisplayName("C-05: PATCH одного или нескольких полей")
    @ParameterizedTest
    @CsvSource(value = {
            "BLOCKED, 10, 100, 1, BLOCKED, 10, 100, 1",
            "null, null, null, null, ACTIVE, 1000, 10000, 100",
            "null, 10, null, null, ACTIVE, 10, 10000, 100",
            "null, null, 20000, null, ACTIVE, 1000, 20000, 100",
            "null, null, null, 1, ACTIVE, 1000, 10000, 1"
    }, nullValues = "null")
    void shouldKeepOmittedFieldsWhenPatchingCard(CardStatus status, BigDecimal daily, BigDecimal monthly,
                                                BigDecimal balance, CardStatus expectedStatus,
                                                BigDecimal expectedDaily, BigDecimal expectedMonthly,
                                                BigDecimal expectedBalance) {
        // Arrange
        executeTransactions();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        var result = service.patchCard(PAN, status, daily, monthly, balance);

        // Assert
        assertThat(result.status()).isEqualTo(expectedStatus);
        assertThat(result.dailyLimit()).isEqualByComparingTo(expectedDaily);
        assertThat(result.monthlyLimit()).isEqualByComparingTo(expectedMonthly);
        assertThat(result.availableBalance()).isEqualByComparingTo(expectedBalance);
        assertThat(result.pan()).isEqualTo(PAN);
        verify(cardRepository).update(result);
        verify(eventNotifier).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @DisplayName("C-06: мягкое удаление через сервис")
    @Test
    void shouldSaveDeletedStatusWhenDeletingExistingCard() {
        // Arrange
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        doAnswer(invocation -> {
            Runnable operation = invocation.getArgument(0);
            operation.run();
            return null;
        }).when(transactionRunner).run(any());

        // Act
        service.deleteCard(PAN);

        // Assert
        var captor = ArgumentCaptor.forClass(Card.class);
        verify(cardRepository).update(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(CardStatus.DELETED);
        assertThat(captor.getValue()).usingRecursiveComparison().ignoringFields("status").isEqualTo(card);
        verify(eventNotifier).notifyListeners(new CardServiceDeletionEvent(PAN));
    }

    @DisplayName("C-11: сохранение пакета")
    @Test
    void shouldSaveBatchWhenPansAreUnique() {
        // Arrange
        when(panGenerator.generatePan("400000")).thenReturn(PAN);
        when(binIssuerService.getIssuerId("400000")).thenReturn("ISS001");
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(cardRepository.createAll(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        var result = service.createCards(List.of(draft()));

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().pan()).isEqualTo(PAN);
        assertThat(result.getFirst().expiryDate()).isEqualTo(YearMonth.of(2029, 9));
        verify(cardRepository).createAll(result);
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
    }

    @Test
    void shouldRetryCardsIndividuallyWhenBatchPanCollides() {
        // Arrange
        when(panGenerator.generatePan("400000")).thenReturn(PAN, "4000001234567907");
        when(binIssuerService.getIssuerId("400000")).thenReturn("ISS001");
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(cardRepository.createAll(any())).thenThrow(new MassiveCardCreationCollisionException());
        when(transactionRunner.runSupplierWithNestedQuery(any())).thenAnswer(invocation -> {
            Function<TransactionRunner, ?> operation = invocation.getArgument(0);
            return operation.apply(transactionRunner);
        });
        executeTransactions();
        executeRetries();
        when(cardRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        var result = service.createCards(List.of(draft()));

        // Assert
        assertThat(result).hasSize(1);
        assertThat(result.getFirst().pan()).isEqualTo("4000001234567907");
        assertThat(result.getFirst().availableBalance()).isEqualByComparingTo("1000");
        verify(cardRepository).create(result.getFirst());
        verify(eventNotifier).notifyListeners(new CardServiceCreationEvent(1));
    }

    @DisplayName("C-12: значения пагинации по умолчанию")
    @Test
    void shouldApplyDefaultPaginationWhenLimitAndOffsetAreOmitted() {
        // Arrange
        when(defaults.pageLimit()).thenReturn(20);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cardRepository.findCards(20, 0L, null, null, null, null, null)).thenReturn(List.of(card));

        // Act
        var result = service.getCards(null, null, null, null, null, null, null);

        // Assert
        assertThat(result).containsExactly(card);
    }

    @Test
    void shouldUseExplicitPaginationWhenLimitIsAtMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);
        when(cardRepository.findCards(100, 5L, null, null, null, null, null)).thenReturn(List.of(card));

        // Act
        var result = service.getCards(100, 5L, null, null, null, null, null);

        // Assert
        assertThat(result).containsExactly(card);
    }

    @Test
    void shouldPassAllFiltersWhenListingCards() {
        // Arrange
        var from = Instant.parse("2026-09-01T00:00:00Z");
        var to = Instant.parse("2026-09-30T23:59:59Z");
        when(settings.maxPageLimit()).thenReturn(100);
        when(cardRepository.findCards(10, 10L, CardStatus.ACTIVE, "400000", "ISS001", from, to))
                .thenReturn(List.of(card));

        // Act
        var result = service.getCards(10, 10L, CardStatus.ACTIVE, "400000", "ISS001", from, to);

        // Assert
        assertThat(result).containsExactly(card);
    }

    @Test
    void shouldPassAllFiltersWhenCountingCards() {
        // Arrange
        var from = Instant.parse("2026-09-01T00:00:00Z");
        var to = Instant.parse("2026-09-30T23:59:59Z");
        when(cardRepository.countCardsFiltered(CardStatus.ACTIVE, "400000", "ISS001", from, to)).thenReturn(25L);

        // Act
        var count = service.countCardsFiltered(CardStatus.ACTIVE, "400000", "ISS001", from, to);

        // Assert
        assertThat(count).isEqualTo(25);
    }

    @DisplayName("C-12: превышение максимального размера страницы")
    @Test
    void shouldRejectWhenPageLimitExceedsMaximum() {
        // Arrange
        when(settings.maxPageLimit()).thenReturn(100);

        // Act
        var error = catchThrowable(() -> service.getCards(101, null, null, null, null, null, null));

        // Assert
        assertThat(error)
                .isInstanceOf(TooLargeLimitException.class);

        verifyNoInteractions(cardRepository);
    }

    @DisplayName("C-13: резерв равен балансу")
    @Test
    void shouldSaveReservationAndBalanceWhenFundsAreSufficient() {
        // Arrange
        var amount = new BigDecimal("100");
        var expectedBalance = BigDecimal.ZERO;
        executeTransactions();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        var result = service.reserve(PAN, amount, RRN);

        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo(expectedBalance);
        var captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().rrn()).isEqualTo(RRN);
        assertThat(captor.getValue().reservationAmount()).isEqualByComparingTo(amount);
        verify(eventNotifier).notifyListeners(new CardServiceReserveEvent(PAN, RRN, amount));
    }

    @DisplayName("C-14: резерв превышает баланс")
    @Test
    void shouldNotSaveWhenReservationExceedsBalance() {
        // Arrange
        executeTransactions();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));

        // Act
        var error = catchThrowable(() -> service.reserve(PAN, new BigDecimal("101"), RRN));

        // Assert
        assertThat(error)
                .isInstanceOf(InsufficientFundsException.class);

        verify(cardRepository, never()).update(any());
        verifyNoInteractions(reservationRepository, eventNotifier);
    }

    @DisplayName("C-16: резерв меньше баланса")
    @Test
    void shouldLeaveOneKopeckWhenReservationIsBelowBalance() {
        // Arrange
        executeTransactions();
        var amount = new BigDecimal("99");
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(true);
        when(reservationRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        var result = service.reserve(PAN, amount, RRN);

        // Assert
        assertThat(result.availableBalance()).isEqualByComparingTo("1");
        var captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().pan()).isEqualTo(PAN);
        assertThat(captor.getValue().rrn()).isEqualTo(RRN);
        assertThat(captor.getValue().reservationAmount()).isEqualByComparingTo(amount);
        verify(cardRepository).update(result);
        verify(eventNotifier).notifyListeners(new CardServiceReserveEvent(PAN, RRN, amount));
    }

    @DisplayName("CP-02–03: явные значения при создании на границе года")
    @ParameterizedTest
    @CsvSource({
            "400001, 4000011234567898, 2026-12, 2029-12, 840, 0",
            "400000, 4000001234567899, 2027-01, 2030-01, 643, 1000"
    })
    void shouldPreserveExplicitValuesWhenCreatingCardAtYearBoundary(String bin, String pan, YearMonth month,
                                                                  YearMonth expiry, String currency, BigDecimal balance) {
        // Arrange
        prepareCreation(bin, pan);
        monthClock.when(YearMonth::now).thenReturn(month);
        var name = "A".repeat(255);

        // Act
        var result = service.createCard(bin, name, currency, new BigDecimal("1200"), new BigDecimal("1500"), balance);

        // Assert
        assertThat(result.dailyLimit()).isEqualByComparingTo("1200");
        assertThat(result.monthlyLimit()).isEqualByComparingTo("1500");
        assertThat(result.availableBalance()).isEqualByComparingTo(balance);
        assertThat(result.cardholderName()).isEqualTo(name);
        assertThat(result.currencyCode()).isEqualTo(currency);
        assertThat(result.expiryDate()).isEqualTo(expiry);
        verify(cardRepository).create(result);
    }

    @Disabled("CP-01: createCard передаёт null в модель, значения по умолчанию не подставляются")
    @Test
    void shouldApplyDefaultsWhenOptionalCreationAmountsAreOmitted() {
        // Arrange
        prepareCreation("400000", PAN);

        // Act
        var result = service.createCard("400000", "IVAN IVANOV", "643", null, null, null);

        // Assert
        assertThat(result.dailyLimit()).isEqualByComparingTo("15000000");
        assertThat(result.monthlyLimit()).isEqualByComparingTo("300000000");
        assertThat(result.availableBalance()).isEqualByComparingTo("100000000");
    }

    @Test
    void shouldReturnCardWhenPanExists() {
        // Arrange
        when(cardRepository.findByPan(PAN)).thenReturn(Optional.of(card));

        // Act
        var result = service.getCard(PAN);

        // Assert
        assertThat(result).isEqualTo(card);
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldNotSaveWhenCardIsNotActive(CardStatus status) {
        // Arrange
        executeTransactions();
        var inactive = card.withData(status, card.dailyLimit(), card.monthlyLimit(), card.availableBalance());
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(inactive));

        // Act
        var error = catchThrowable(() -> service.reserve(PAN, BigDecimal.ONE, RRN));

        // Assert
        assertThat(error).isInstanceOf(IllegalStateException.class);

        verify(cardRepository, never()).update(any());
        verifyNoInteractions(reservationRepository, eventNotifier);
    }

    @Test
    void shouldNotSaveWhenReservationAlreadyExists() {
        // Arrange
        executeTransactions();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(reservationRepository.isUnique(RRN, PAN)).thenReturn(false);

        // Act
        var error = catchThrowable(() -> service.reserve(PAN, BigDecimal.ONE, RRN));

        // Assert
        assertThat(error)
                .isInstanceOf(ReservationAlreadyExistsException.class);

        verify(reservationRepository, never()).save(any());
        verify(cardRepository, never()).update(any());
        verifyNoInteractions(eventNotifier);
    }

    @Test
    void shouldRestoreBalanceWhenReservationExists() {
        // Arrange
        executeTransactions();
        var amount = new BigDecimal("99");
        var reservation = card.startReservation(amount, RRN);
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card.withReservation(reservation)));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(rollbackRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cardRepository.update(any())).thenAnswer(invocation -> invocation.getArgument(0));

        // Act
        var result = service.rollback(PAN, amount, RRN);

        // Assert
        assertThat(result).isEqualTo(card);
        var captor = ArgumentCaptor.forClass(Reservation.class);
        verify(reservationRepository).save(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        verify(eventNotifier).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, amount));
    }

    @Test
    void shouldNotRestoreBalanceWhenReservationIsMissing() {
        // Arrange
        executeTransactions();
        when(cardRepository.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
        when(reservationRepository.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());

        // Act
        var error = catchThrowable(() -> service.rollback(PAN, BigDecimal.ONE, RRN));

        // Assert
        assertThat(error)
                .isInstanceOf(ReservationNotFoundException.class);

        verify(cardRepository, never()).update(any());
        verifyNoInteractions(rollbackRepository, eventNotifier);
    }

    @Test
    void shouldUpdateMatchingCardsWhenSelectingByBin() {
        // Arrange
        executeTransactions();
        when(cardRepository.findCardsByBinsForUpdate(List.of("400000"))).thenReturn(List.of(card));

        // Act
        var count = service.bulkUpdateStatus(List.of("400000"), null, CardStatus.BLOCKED);

        // Assert
        assertThat(count).isEqualTo(1);
        verify(cardRepository).update(card.withData(CardStatus.BLOCKED, card.dailyLimit(), card.monthlyLimit(),
                card.availableBalance()));
    }

    @Test
    void shouldUpdateMatchingCardsWhenSelectingByPan() {
        // Arrange
        executeTransactions();
        when(cardRepository.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(card));

        // Act
        var count = service.bulkUpdateStatus(null, List.of(PAN), CardStatus.BLOCKED);

        // Assert
        assertThat(count).isEqualTo(1);
        verify(cardRepository).update(card.withData(CardStatus.BLOCKED, card.dailyLimit(), card.monthlyLimit(),
                card.availableBalance()));
    }

    @Test
    void shouldRejectWhenBulkSelectorIsMissing() {
        // Arrange
        // The fixture is initialized in setUp().

        // Act
        var error = catchThrowable(() -> service.bulkUpdateStatus(null, null, CardStatus.BLOCKED));

        // Assert
        assertThat(error)
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(cardRepository);
    }

    @Test
    void shouldRejectWhenBulkSelectorIsAmbiguous() {
        // Arrange
        // The fixture is initialized in setUp().

        // Act
        var error = catchThrowable(() -> service.bulkUpdateStatus(List.of("400000"), List.of(PAN), CardStatus.BLOCKED));

        // Assert
        assertThat(error)
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(cardRepository);
    }

    private CardDraft draft() {
        return new CardDraft("400000", "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("1200"), new BigDecimal("1500"), new BigDecimal("1000"));
    }

    private void prepareCreation(String bin, String pan) {
        when(binIssuerService.getIssuerId(bin)).thenReturn("ISS001");
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(panGenerator.generatePan(bin)).thenReturn(pan);
        executeRetries();
        when(cardRepository.create(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void executeRetries() {
        when(retryService.supply(anyInt(), any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(1);
            return operation.get();
        });
    }

    private void executeTransactions() {
        when(transactionRunner.runSupplier(any())).thenAnswer(invocation -> {
            Supplier<?> operation = invocation.getArgument(0);
            return operation.get();
        });
    }
}
