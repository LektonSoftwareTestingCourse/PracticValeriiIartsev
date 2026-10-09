package com.processing.authorization.services;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Answers.RETURNS_DEFAULTS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final Instant TRANSACTION_TS = Instant.parse("2026-09-24T12:00:00Z");

    private static final LocalDate TRANSACTION_DATE = LocalDate.ofInstant(TRANSACTION_TS, ZoneOffset.UTC);

    @Mock
    private LimitUsageRepository limitUsageRepository;
    @Mock
    private AuthorizationEventNotifier eventNotifier;
    @Mock
    private BinLookupClient binLookupClient;
    @Mock
    private CardManagementClient cardManagementClient;
    @InjectMocks
    private AuthServiceImpl service;

    private AuthorizationRequest request;

    @BeforeEach
    void setUp() {
        request = new AuthorizationRequest(
                "0100",
                "000001",
                "4000001234567899",
                "000000",
                new BigDecimal("100"),
                "643",
                TRANSACTION_TS,
                "TERM0001",
                "POS",
                "MERCH0000000001",
                "5411",
                "ACQ001",
                "ISS001"
        );
    }

    @DisplayName("A-01: активная карта")
    @Test
    void shouldApproveWhenCardIsActive() {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10), new BigDecimal("1000"));
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        allowLimits(card);

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertApproved(result);
        verify(cardManagementClient).reserve(request.amount(), result.rrn(), request.pan());
        verify(limitUsageRepository).upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                card.dailyLimit(), card.monthlyLimit());
    }

    @DisplayName("A-02–A-04: неактивная, заблокированная, просроченная карта")
    @ParameterizedTest
    @CsvSource(
            value = {
                    "INACTIVE, 05, CARD_INACTIVE",
                    "BLOCKED, 05, CARD_BLOCKED",
                    "EXPIRED, 54, CARD_EXPIRED"
            },
            nullValues = "null"
    )
    void shouldDeclineBeforeCheckingLimitsWhenCardIsNotActive(CardModelStatus status, String code, String reason) {
        // Arrange
        when(cardManagementClient.getCard(request.pan())).thenReturn(card(status, YearMonth.of(2029, 9),
                BigDecimal.ZERO));

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.rrn()).isNull();
        assertThat(result.authCode()).isNull();
        assertThat(result.declineReason()).isEqualTo(reason);
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-05: отсутствующий PAN")
    @Test
    void shouldDeclineWhenCardDoesNotExist() {
        // Arrange
        when(cardManagementClient.getCard(request.pan())).thenThrow(new CardNotFoundException("missing"));

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertDeclined(result, "14", "CARD_NOT_FOUND");
        verifyNoInteractions(limitUsageRepository, binLookupClient);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-06–A-08: прошлый, текущий, будущий месяц срока")
    @ParameterizedTest
    @CsvSource({"2026-08, false", "2026-09, true", "2026-10, true"})
    void shouldCheckExpiryRelativeToTransactionMonthWhenCardIsActive(YearMonth expiry, boolean valid) {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, expiry, new BigDecimal("1000"));
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        if (valid) {
            allowLimits(card);
        }

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        if (valid) {
            assertApproved(result);
            verify(cardManagementClient).reserve(request.amount(), result.rrn(), request.pan());
        } else {
            assertDeclined(result, "54", "CARD_EXPIRED");
            verifyNoInteractions(limitUsageRepository);
            verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
        }
    }

    @DisplayName("A-09–A-14: аргументы и результат репозитория; арифметика SQL не исполняется")
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "A-09, 299, 1500, 0", "A-10, 300, 1500, 1", "A-11, 301, 1500, 1",
            "A-12, 1200, 599, 0", "A-13, 1200, 600, 1", "A-14, 1200, 601, 1"
    })
    void shouldHandleRepositoryDecisionWhenLimitBoundaryFixtureIsProvided(
            String caseId, BigDecimal dailyLimit, BigDecimal monthlyLimit, int updatedRows) {
        // Arrange
        var card = new CardModel(UUID.randomUUID(), request.pan(), "400000", "IVAN IVANOV", YearMonth.of(2026, 10),
                CardModelStatus.ACTIVE, "643", dailyLimit, monthlyLimit, new BigDecimal("1000"), "ISS001",
                TRANSACTION_TS);
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        when(limitUsageRepository.upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                dailyLimit, monthlyLimit)).thenReturn(updatedRows);

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.responseCode()).as(caseId).isEqualTo(updatedRows == 1 ? "00" : "61");
        verify(limitUsageRepository).upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                dailyLimit, monthlyLimit);
        if (updatedRows == 1) {
            assertApproved(result);
            verify(cardManagementClient).reserve(request.amount(), result.rrn(), request.pan());
        } else {
            assertDeclined(result, "61", "EXCEEDS_AMOUNT_LIMIT");
            verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
        }
    }

    @DisplayName("A-15: баланс 99")
    @Test
    void shouldDeclineWhenBalanceIsInsufficient() {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10), new BigDecimal("99"));
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        // A-15 isolates the balance boundary; check priority is tested separately by P-14/P-20.
        lenient().when(limitUsageRepository.upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                card.dailyLimit(), card.monthlyLimit())).thenReturn(1);

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.responseCode()).isEqualTo("51");
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-16–A-17: баланс 100 и 101")
    @ParameterizedTest
    @CsvSource({"100", "101"})
    void shouldApproveWhenBalanceCoversAmount(BigDecimal balance) {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2029, 9), balance);
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        allowLimits(card);

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.stan()).isEqualTo(request.stan());
        assertThat(result.rrn()).matches("\\d{12}");
        assertThat(result.authCode()).matches("[A-Z0-9]{6}");
        verify(cardManagementClient).reserve(request.amount(), result.rrn(), request.pan());
        verify(limitUsageRepository).upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                card.dailyLimit(), card.monthlyLimit());
    }

    @DisplayName("A-18: недоступный CMS — 05 / ISSUER_TIMEOUT")
    @Test
    void shouldDeclineWhenCmsIsUnavailable() {
        // Arrange
        when(cardManagementClient.getCard(request.pan())).thenThrow(new ServiceUnavailableException("unavailable"));

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        // AUTH section 5 requires 05 / ISSUER_TIMEOUT; this assertion exposes the current defect.
        assertDeclined(result, "05", "ISSUER_TIMEOUT");
        verifyNoInteractions(limitUsageRepository, binLookupClient);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-20–A-21: авторизация суммы 1 и 2")
    @ParameterizedTest
    @CsvSource({"1", "2"})
    void shouldReserveExactAmountWhenAmountIsAtMinimumBoundary(BigDecimal amount) {
        // Arrange
        request = withAmount(amount);
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10), new BigDecimal("1000"));
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        allowLimits(card);

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertApproved(result);
        verify(cardManagementClient).reserve(amount, result.rrn(), request.pan());
        verify(limitUsageRepository).upsertLimitUsage(request.pan(), TRANSACTION_DATE, amount,
                card.dailyLimit(), card.monthlyLimit());
    }

    @DisplayName("P-01–P-22: решения и приоритет отказов")
    @ParameterizedTest(name = "{0}: {1}, expiry={5}, code={8}")
    @CsvSource(value = {
            "P-01, BLOCKED, 99, 99, 99, 2026-08, POS, 5411, 05, CARD_BLOCKED",
            "P-02, ACTIVE, 99, 99, 99, 2026-08, POS, 5411, 54, CARD_EXPIRED",
            "P-03, ACTIVE, 101, 101, 101, 2026-10, ATM, 5411, 00, null",
            "P-04, ACTIVE, 100, 100, 100, 2026-10, ECOM, 5812, 00, null",
            "P-05, ACTIVE, 100, 101, 101, 2026-09, POS, 3501, 00, null",
            "P-06, ACTIVE, 101, 100, 99, 2026-09, ATM, 5732, 51, INSUFFICIENT_FUNDS",
            "P-07, ACTIVE, 101, 101, 101, 2026-08, ATM, 3501, 54, CARD_EXPIRED",
            "P-08, ACTIVE, 101, 101, 101, 2026-08, ECOM, 5732, 54, CARD_EXPIRED",
            "P-09, ACTIVE, 99, 100, 100, 2026-10, POS, 3501, 61, EXCEEDS_AMOUNT_LIMIT",
            "P-10, ACTIVE, 99, 99, 100, 2026-09, ECOM, 5732, 61, EXCEEDS_AMOUNT_LIMIT",
            "P-11, ACTIVE, 101, 101, 101, 2026-08, POS, 5812, 54, CARD_EXPIRED",
            "P-12, ACTIVE, 100, 101, 99, 2026-09, ECOM, 5411, 51, INSUFFICIENT_FUNDS",
            "P-13, EXPIRED, 101, 101, 101, 2026-10, POS, 5411, 54, CARD_EXPIRED",
            "P-14, ACTIVE, 99, 99, 99, 2026-10, ATM, 5812, 61, EXCEEDS_AMOUNT_LIMIT",
            "P-15, ACTIVE, 101, 99, 101, 2026-10, ECOM, 3501, 61, EXCEEDS_AMOUNT_LIMIT",
            "P-16, ACTIVE, 100, 100, 101, 2026-10, ATM, 5411, 00, null",
            "P-17, ACTIVE, 99, 101, 101, 2026-09, POS, 5812, 61, EXCEEDS_AMOUNT_LIMIT",
            "P-18, ACTIVE, 101, 101, 100, 2026-10, ATM, 5411, 00, null",
            "P-19, BLOCKED, 101, 101, 101, 2026-10, POS, 5411, 05, CARD_BLOCKED",
            "P-20, ACTIVE, 100, 99, 99, 2026-10, POS, 3501, 61, EXCEEDS_AMOUNT_LIMIT",
            "P-21, INACTIVE, 101, 101, 101, 2026-10, POS, 5411, 05, CARD_INACTIVE",
            "P-22, ACTIVE, 100, 101, 101, 2026-10, POS, 5732, 00, null"
    }, nullValues = "null")
    void shouldFollowDecisionPriorityWhenPairwiseScenarioIsExecuted(
            String caseId, CardModelStatus status, int dailyRemaining, int monthlyRemaining, int balance,
            YearMonth expiry, String terminalType, String mcc, String code, String reason
    ) {
        // Arrange
        var timestamp = Instant.parse("2026-09-24T12:00:00Z");
        var date = LocalDate.of(2026, 9, 24);
        var request = new AuthorizationRequest(
                "0100",
                "000001",
                "4000001234567899",
                "000000",
                new BigDecimal("100"),
                "643",
                timestamp,
                "TERM0001",
                terminalType,
                "MERCH0000000001",
                mcc,
                "ACQ001",
                "ISS001"
        );
        var card = new CardModel(
                UUID.randomUUID(),
                request.pan(),
                "400000",
                "IVAN IVANOV",
                expiry,
                status,
                "643",
                BigDecimal.valueOf(200L + dailyRemaining),
                BigDecimal.valueOf(500L + monthlyRemaining),
                BigDecimal.valueOf(balance),
                "ISS001",
                timestamp
        );

        int limitResult = dailyRemaining >= 100 && monthlyRemaining >= 100 ? 1 : 0;
        var repository = mock(
                LimitUsageRepository.class, invocation ->
                        invocation.getMethod().getName().equals("upsertLimitUsage")
                                ? limitResult : RETURNS_DEFAULTS.answer(invocation)
        );
        var service = new AuthServiceImpl(repository, eventNotifier, binLookupClient, cardManagementClient);
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.responseCode()).as(caseId).isEqualTo(code);
        assertThat(result.declineReason()).as(caseId).isEqualTo(reason);
        assertThat(result.stan()).isEqualTo(request.stan());
        if (code.equals("00")) {
            assertThat(result.status()).isEqualTo("APPROVED");
            assertThat(result.rrn()).matches("\\d{12}");
            assertThat(result.authCode()).matches("[A-Z0-9]{6}");
            verify(cardManagementClient).reserve(request.amount(), result.rrn(), request.pan());
            verify(repository).upsertLimitUsage(request.pan(), date, request.amount(), card.dailyLimit(),
                    card.monthlyLimit());
        } else {
            assertThat(result.status()).isEqualTo("DECLINED");
            assertThat(result.rrn()).isNull();
            assertThat(result.authCode()).isNull();
            verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
        }
    }

    @ParameterizedTest
    @CsvSource({"2026-09-30T23:59:59Z, true", "2026-10-01T00:00:00Z, false"})
    void shouldCheckExpiryAtMonthBoundaryWhenCardIsActive(Instant transactionTime, boolean valid) {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 9), new BigDecimal("100"));
        request = new AuthorizationRequest(request.mti(), request.stan(), request.pan(), request.processingCode(),
                request.amount(), request.currencyCode(), transactionTime, request.terminalId(), request.terminalType(),
                request.merchantId(), request.mcc(), request.acquirerId(), request.issuerId());
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        if (valid) {
            when(limitUsageRepository.upsertLimitUsage(eq(request.pan()), any(LocalDate.class), eq(request.amount()),
                    eq(card.dailyLimit()), eq(card.monthlyLimit()))).thenReturn(1);
        }

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.status()).isEqualTo(valid ? "APPROVED" : "DECLINED");
        assertThat(result.responseCode()).isEqualTo(valid ? "00" : "54");
        if (!valid) {
            verifyNoInteractions(limitUsageRepository);
            verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
        }
    }

    @Test
    void shouldDeclineWhenCardStatusIsMissing() {
        // Arrange
        when(cardManagementClient.getCard(request.pan())).thenReturn(card(null, YearMonth.of(2026, 10),
                BigDecimal.TEN));

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertDeclined(result, "05", "UNKNOWN_REASON");
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void shouldRetryLimitUpdateWhenConcurrentInsertConflicts(boolean retrySucceeds) {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2029, 9), new BigDecimal("100"));
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        var update = when(limitUsageRepository.upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                card.dailyLimit(), card.monthlyLimit())).thenThrow(new DuplicateKeyException("concurrent insert"));
        if (retrySucceeds) {
            update.thenReturn(1);
        } else {
            update.thenThrow(new IllegalStateException("database unavailable"));
        }

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.responseCode()).isEqualTo(retrySucceeds ? "00" : "96");
        verify(limitUsageRepository, times(2)).upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                card.dailyLimit(), card.monthlyLimit());
        if (!retrySucceeds) {
            verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
        }
    }

    @Test
    void shouldDeclineWhenLimitRepositoryFailsWithoutConflict() {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10), new BigDecimal("1000"));
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        when(limitUsageRepository.upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                card.dailyLimit(), card.monthlyLimit())).thenThrow(new IllegalStateException("database unavailable"));

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertDeclined(result, "96", "DB_UNAVAILABLE");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest
    @MethodSource("cardLookupFailures")
    void shouldDeclineWithoutSideEffectsWhenCardLookupFails(RuntimeException error, String code, String reason) {
        // Arrange
        when(cardManagementClient.getCard(request.pan())).thenThrow(error);

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.rrn()).isNull();
        assertThat(result.authCode()).isNull();
        assertThat(result.declineReason()).isEqualTo(reason);
        verifyNoInteractions(limitUsageRepository, binLookupClient);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineWhenFundsBecomeInsufficientDuringReservation() {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2029, 9), new BigDecimal("100"));
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        allowLimits(card);
        doThrow(new InsufficientFundsException("balance changed"))
                .when(cardManagementClient).reserve(eq(request.amount()), anyString(), eq(request.pan()));

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("51");
        assertThat(result.authCode()).isNull();
        assertThat(result.declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
    }

    @ParameterizedTest
    @MethodSource("reservationFailures")
    void shouldReturnSpecificReasonWhenReservationFails(RuntimeException error, String code, String reason) {
        // Arrange
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10), new BigDecimal("1000"));
        when(cardManagementClient.getCard(request.pan())).thenReturn(card);
        allowLimits(card);
        doThrow(error).when(cardManagementClient).reserve(eq(request.amount()), anyString(), eq(request.pan()));

        // Act
        var result = service.authorize(request, Instant.now());

        // Assert
        assertDeclined(result, code, reason);
        verify(cardManagementClient).reserve(eq(request.amount()), anyString(), eq(request.pan()));
    }

    @Test
    void shouldApproveWhenCmsRollsBackReservation() {
        // Arrange
        var request = rollbackRequest();

        // Act
        var result = service.rollback(request, Instant.now());

        // Assert
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.declineReason()).isNull();
        assertThat(result.rrn()).isEqualTo(request.rrn());
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier).notify(new AuthServiceRollbackEvent(request.pan()));
        verifyNoInteractions(binLookupClient);
    }

    @ParameterizedTest
    @MethodSource("rollbackFailures")
    void shouldDeclineWithSpecificReasonWhenCmsRollbackFails(RuntimeException error, String code, String reason) {
        // Arrange
        var request = rollbackRequest();
        doThrow(error).when(cardManagementClient).rollback(request);

        // Act
        var result = service.rollback(request, Instant.now());

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.declineReason()).isEqualTo(reason);
        assertThat(result.rrn()).isEqualTo(request.rrn());
        verify(cardManagementClient).rollback(request);
        verify(eventNotifier, never()).notify(new AuthServiceRollbackEvent(request.pan()));
        verifyNoInteractions(binLookupClient);
    }

    @Test
    void shouldKeepRrnUniqueWhenSequenceCrossesBlockBoundary() {
        // Arrange
        var date = LocalDate.of(2026, 9, 24);
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(1L, 2L);
        try (var clock = mockStatic(LocalDate.class)) {
            clock.when(LocalDate::now).thenReturn(date);

            // Act
            var rrns = IntStream.range(0, 1001).mapToObj(i -> service.generateRRN()).toList();

            // Assert
            assertThat(rrns).hasSize(1001).doesNotHaveDuplicates();
            assertThat(rrns).allSatisfy(rrn -> assertThat(rrn).matches("\\d{12}"));
            assertThat(rrns.get(0)).isEqualTo("626700001000");
            assertThat(rrns.get(999)).isEqualTo("626700001999");
            assertThat(rrns.get(1000)).isEqualTo("626700002000");
            verify(limitUsageRepository, times(2)).fetchRrnBlock();
        }
    }

    static Stream<Arguments> cardLookupFailures() {
        return Stream.of(
                arguments(new CardNotFoundException("not found"), "14", "CARD_NOT_FOUND"),
                arguments(new InvalidGetCardRequestException("invalid"), "14", "CARD_NOT_FOUND"),
                arguments(new ServiceUnavailableException("unavailable"), "05", "ISSUER_TIMEOUT"),
                arguments(new ResourceAccessException("timeout"), "05", "ISSUER_TIMEOUT"),
                arguments(new InternalCardManagerException("internal"), "05", "ISSUER_TIMEOUT"),
                arguments(new GetCardException("get failed"), "05", "ISSUER_TIMEOUT"),
                arguments(new PaymentRequiredException("blocked"), "05", "CARD_BLOCKED"),
                arguments(new IllegalStateException("unexpected"), "05", "UNKNOWN_REASON"));
    }

    static Stream<Arguments> reservationFailures() {
        return Stream.of(
                arguments(new CardNotFoundException("missing"), "14", "CARD_NOT_FOUND"),
                arguments(new InvalidReserveRequestException("invalid"), "14", "CARD_NOT_FOUND"),
                arguments(new ServiceUnavailableException("unavailable"), "05", "ISSUER_TIMEOUT"),
                arguments(new ResourceAccessException("timeout"), "05", "ISSUER_TIMEOUT"),
                arguments(new InternalCardManagerException("internal"), "05", "ISSUER_TIMEOUT"),
                arguments(new InsufficientFundsException("balance changed"), "51", "INSUFFICIENT_FUNDS"),
                arguments(new ReserveException("reserve failed"), "96", "RESERVATION_FAILED"),
                arguments(new IllegalStateException("unexpected"), "05", "UNKNOWN_REASON"));
    }

    static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                arguments(new CardNotFoundException("missing"), "14", "TRANSACTION_NOT_FOUND"),
                arguments(new InvalidRollbackRequestException("invalid"), "14", "TRANSACTION_NOT_FOUND"),
                arguments(new ServiceUnavailableException("unavailable"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new InternalCardManagerException("internal"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new RollbackConflictException("duplicate"), "05", "ALREADY_ROLLED_BACK"),
                arguments(new RollbackFailureException("failed"), "96", "ROLLBACK_FAILED"),
                arguments(new IllegalStateException("unexpected"), "05", "UNKNOWN_REASON"));
    }

    private RollbackRequest rollbackRequest() {
        return new RollbackRequest("123456789012", "4000001234567899", new BigDecimal("100"));
    }

    private AuthorizationRequest withAmount(BigDecimal amount) {
        return new AuthorizationRequest(request.mti(), request.stan(), request.pan(), request.processingCode(),
                amount, request.currencyCode(), request.transmissionDateTime(), request.terminalId(),
                request.terminalType(),
                request.merchantId(), request.mcc(), request.acquirerId(), request.issuerId());
    }

    private void assertApproved(AuthorizationResponse result) {
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.declineReason()).isNull();
        assertThat(result.stan()).isEqualTo(request.stan());
        assertThat(result.rrn()).matches("\\d{12}");
        assertThat(result.authCode()).matches("[A-Z0-9]{6}");
    }

    private void assertDeclined(AuthorizationResponse result, String code, String reason) {
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.declineReason()).isEqualTo(reason);
        assertThat(result.stan()).isEqualTo(request.stan());
        assertThat(result.rrn()).isNull();
        assertThat(result.authCode()).isNull();
    }

    private void allowLimits(CardModel card) {
        when(limitUsageRepository.upsertLimitUsage(request.pan(), TRANSACTION_DATE, request.amount(),
                card.dailyLimit(), card.monthlyLimit())).thenReturn(1);
    }

    private CardModel card(CardModelStatus status, YearMonth expiry, BigDecimal balance) {
        return new CardModel(UUID.fromString("00000000-0000-0000-0000-000000000001"), request.pan(), "400000",
                "IVAN IVANOV",
                expiry, status, "643", new BigDecimal("1000"), new BigDecimal("10000"), balance, "ISS001",
                Instant.parse("2026-09-24T12:00:00Z"));
    }
}
