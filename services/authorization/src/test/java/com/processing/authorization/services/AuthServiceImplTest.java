package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
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
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234567899";
    private static final Instant REQUEST_TIME = Instant.parse("2026-09-24T10:00:00Z");
    private static final Instant RESPONSE_TIME = REQUEST_TIME.plusMillis(12);
    private static final LocalDate REQUEST_DATE = LocalDate.of(2026, 9, 24);
    private static final UUID CARD_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private LimitUsageRepository limitUsageRepository;
    @Mock
    private AuthorizationEventNotifier eventNotifier;
    @Mock
    private BinLookupClient binLookupClient;
    @Mock
    private CardManagementClient cardManagementClient;
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private MockedStatic<Instant> instantClock;
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private MockedStatic<LocalDate> dateClock;

    private AuthServiceImpl service;
    private AuthorizationRequest request;

    @BeforeEach
    void setUp() {
        instantClock.when(Instant::now).thenReturn(RESPONSE_TIME);
        dateClock.when(LocalDate::now).thenReturn(REQUEST_DATE);
        service = new AuthServiceImpl(limitUsageRepository, eventNotifier, binLookupClient, cardManagementClient);
        request = request(new BigDecimal("100"), REQUEST_TIME, "POS", "5411");
    }

    @DisplayName("A-01, A-16–17, A-20–21: одобрение и граничные суммы/баланс")
    @ParameterizedTest
    @CsvSource({"100, 1000", "100, 100", "100, 101", "1, 1000", "2, 1000"})
    void shouldReserveExactAmountWhenActiveCardHasEnoughFunds(BigDecimal amount, BigDecimal balance) {
        // Arrange
        request = request(amount, REQUEST_TIME, "POS", "5411");
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10), balance);
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        allowLimits(card);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertApproved(result);
        verify(cardManagementClient).reserve(amount, result.rrn(), PAN);
        verify(eventNotifier).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @DisplayName("P-03–05, P-16, P-18, P-22: одобряемые pairwise-комбинации")
    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "P-03, 101, 101, 101, 2026-10, ATM, 5411",
            "P-04, 100, 100, 100, 2026-10, ECOM, 5812",
            "P-05, 100, 101, 101, 2026-09, POS, 3501",
            "P-16, 100, 100, 101, 2026-10, ATM, 5411",
            "P-18, 101, 101, 100, 2026-10, ATM, 5411",
            "P-22, 100, 101, 101, 2026-10, POS, 5732"
    })
    void shouldApproveWhenPairwiseInputsPassAllDecisions(String caseId, int daily, int monthly, int balance,
                                                        YearMonth expiry, String terminalType, String mcc) {
        // Arrange
        request = request(new BigDecimal("100"), REQUEST_TIME, terminalType, mcc);
        var card = cardWithLimits(expiry, BigDecimal.valueOf(balance), BigDecimal.valueOf(200L + daily),
                BigDecimal.valueOf(500L + monthly));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        allowLimits(card);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertThat(result.responseCode()).as(caseId).isEqualTo("00");
        assertApproved(result);
        verify(cardManagementClient).reserve(request.amount(), result.rrn(), PAN);
    }

    @DisplayName("A-02–04, P-01, P-13, P-19, P-21: статус имеет приоритет над балансом и сроком")
    @ParameterizedTest
    @CsvSource({
            "A-02, INACTIVE, 2026-10, 1000, 05, CARD_INACTIVE",
            "A-03, BLOCKED, 2026-10, 1000, 05, CARD_BLOCKED",
            "A-04, EXPIRED, 2026-10, 1000, 54, CARD_EXPIRED",
            "P-01, BLOCKED, 2026-08, 99, 05, CARD_BLOCKED",
            "P-13, EXPIRED, 2026-10, 101, 54, CARD_EXPIRED",
            "P-19, BLOCKED, 2026-10, 101, 05, CARD_BLOCKED",
            "P-21, INACTIVE, 2026-10, 101, 05, CARD_INACTIVE"
    })
    void shouldDeclineBeforeLimitsAndReserveWhenCardIsNotActive(String caseId, CardModelStatus status,
                                                               YearMonth expiry, BigDecimal balance,
                                                               String code, String reason) {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(card(status, expiry, balance));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, code, reason);
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-06, P-02/P-07/P-08/P-11: срок проверяется раньше баланса")
    @ParameterizedTest
    @CsvSource({
            "A-06, 2026-08, 1000, POS, 5411",
            "P-02, 2026-08, 99, POS, 5411",
            "P-07, 2026-08, 101, ATM, 3501",
            "P-08, 2026-08, 101, ECOM, 5732",
            "P-11, 2026-08, 101, POS, 5812"
    })
    void shouldDeclineBeforeReserveWhenExpiryMonthHasPassed(String caseId, YearMonth expiry, BigDecimal balance,
                                                           String terminalType, String mcc) {
        // Arrange
        request = request(new BigDecimal("100"), REQUEST_TIME, terminalType, mcc);
        when(cardManagementClient.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, expiry, balance));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "54", "CARD_EXPIRED");
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-07–08: текущий месяц включён, включая последнюю секунду")
    @ParameterizedTest
    @ValueSource(strings = {"2026-09-01T00:00:00Z", "2026-09-24T10:00:00Z", "2026-09-30T23:59:59Z"})
    void shouldApproveWhenTransactionIsWithinExpiryMonth(Instant transmissionTime) {
        // Arrange
        request = request(new BigDecimal("100"), transmissionTime, "POS", "5411");
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2026, 9), new BigDecimal("1000"));
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        allowLimits(card);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertApproved(result);
        verify(cardManagementClient).reserve(request.amount(), result.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenTransactionIsAtStartOfMonthAfterExpiry() {
        // Arrange
        request = request(new BigDecimal("100"), Instant.parse("2026-10-01T00:00:00Z"), "POS", "5411");
        when(cardManagementClient.getCard(PAN))
                .thenReturn(card(CardModelStatus.ACTIVE, YearMonth.of(2026, 9), new BigDecimal("1000")));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "54", "CARD_EXPIRED");
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-15, P-06/P-12: недостаточный баланс")
    @ParameterizedTest
    @CsvSource({"0, 2026-10, POS", "99, 2026-10, POS", "99, 2026-09, ATM", "99, 2026-09, ECOM"})
    void shouldDeclineBeforeUpdatingLimitsWhenBalanceIsInsufficient(BigDecimal balance, YearMonth expiry,
                                                                  String terminalType) {
        // Arrange
        request = request(new BigDecimal("100"), REQUEST_TIME, terminalType, "5411");
        when(cardManagementClient.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, expiry, balance));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "51", "INSUFFICIENT_FUNDS");
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-09/A-12, P-09/P-10/P-15/P-17: реакция на отказ репозитория лимитов")
    @ParameterizedTest
    @CsvSource({
            "A-09, 299, 1500, 1000, 2026-10, POS, 5411",
            "A-12, 1200, 599, 1000, 2026-10, POS, 5411",
            "P-09, 299, 600, 100, 2026-10, POS, 3501",
            "P-10, 299, 599, 100, 2026-09, ECOM, 5732",
            "P-15, 301, 599, 101, 2026-10, ECOM, 3501",
            "P-17, 299, 601, 101, 2026-09, POS, 5812"
    })
    void shouldDeclineWithoutReserveWhenRepositoryRejectsLimitUpdate(String caseId, BigDecimal daily,
                                                                   BigDecimal monthly, BigDecimal balance,
                                                                   YearMonth expiry, String terminalType, String mcc) {
        // Arrange: SQL-арифметика не исполняется; проверяется решение сервиса при updatedRows=0.
        request = request(new BigDecimal("100"), REQUEST_TIME, terminalType, mcc);
        var card = cardWithLimits(expiry, balance, daily, monthly);
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(limitUsageRepository.upsertLimitUsage(PAN, REQUEST_DATE, request.amount(), daily, monthly)).thenReturn(0);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @DisplayName("A-10/A-11/A-13/A-14: передача границ лимитов и обработка разрешения репозитория")
    @ParameterizedTest
    @CsvSource({"300, 1500", "301, 1500", "1200, 600", "1200, 601"})
    void shouldApproveWhenRepositoryAcceptsLimitUpdate(BigDecimal daily, BigDecimal monthly) {
        // Arrange
        var card = cardWithLimits(YearMonth.of(2026, 10), new BigDecimal("1000"), daily, monthly);
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        allowLimits(card);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertApproved(result);
        verify(cardManagementClient).reserve(request.amount(), result.rrn(), PAN);
    }

    @ParameterizedTest
    @MethodSource("lookupFailures")
    void shouldReturnSpecificDeclineWhenCardLookupFails(RuntimeException failure, String code, String reason) {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(failure);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, code, reason);
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineWhenCardStatusIsMissing() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenReturn(card(null, YearMonth.of(2026, 10), BigDecimal.TEN));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "05", "UNKNOWN_REASON");
        verifyNoInteractions(limitUsageRepository);
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest
    @MethodSource("issuerResults")
    void shouldApproveWhenBinLookupReturnsIssuerOrEmpty(Optional<String> issuer) {
        // Arrange
        var card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(binLookupClient.getIssuerId(PAN)).thenReturn(issuer);
        allowLimits(card);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertApproved(result);
        verify(cardManagementClient).reserve(request.amount(), result.rrn(), PAN);
    }

    @Test
    void shouldApproveWhenLimitUpdateSucceedsAfterDuplicateKey() {
        // Arrange
        var card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(limitUsageRepository.upsertLimitUsage(PAN, REQUEST_DATE, request.amount(), card.dailyLimit(),
                card.monthlyLimit())).thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(1);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertApproved(result);
        verify(cardManagementClient).reserve(request.amount(), result.rrn(), PAN);
    }

    @Test
    void shouldDeclineWhenLimitUpdateIsRejectedAfterDuplicateKey() {
        // Arrange
        var card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(limitUsageRepository.upsertLimitUsage(PAN, REQUEST_DATE, request.amount(), card.dailyLimit(),
                card.monthlyLimit())).thenThrow(new DuplicateKeyException("concurrent insert")).thenReturn(0);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineWhenLimitRetryFails() {
        // Arrange
        var card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(limitUsageRepository.upsertLimitUsage(PAN, REQUEST_DATE, request.amount(), card.dailyLimit(),
                card.monthlyLimit())).thenThrow(new DuplicateKeyException("concurrent insert"))
                .thenThrow(new IllegalStateException("database unavailable"));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "96", "DB_UNAVAILABLE");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void shouldDeclineWhenLimitRepositoryIsUnavailable() {
        // Arrange
        var card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        when(limitUsageRepository.upsertLimitUsage(PAN, REQUEST_DATE, request.amount(), card.dailyLimit(),
                card.monthlyLimit())).thenThrow(new IllegalStateException("database unavailable"));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "96", "DB_UNAVAILABLE");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest
    @MethodSource("reservationFailures")
    void shouldDeclineWhenReservationFails(RuntimeException failure, String code, String reason) {
        // Arrange
        var card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        allowLimits(card);
        doThrow(failure).when(cardManagementClient).reserve(eq(request.amount()), anyString(), eq(PAN));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, code, reason);
        verify(eventNotifier, never()).notify(new AuthServiceAuthorizeEvent(PAN));
    }

    @Test
    void shouldApproveWhenCmsRollsBackReservation() {
        // Arrange
        var rollback = new RollbackRequest("123456789012", PAN, new BigDecimal("100"));

        // Act
        var result = service.rollback(rollback, REQUEST_TIME);

        // Assert
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.declineReason()).isNull();
        assertThat(result.rrn()).isEqualTo(rollback.rrn());
        assertThat(result.processingTimeMs()).isEqualTo(12);
        verify(cardManagementClient).rollback(rollback);
        verify(eventNotifier).notify(new AuthServiceRollbackEvent(PAN));
    }

    @ParameterizedTest
    @MethodSource("rollbackFailures")
    void shouldDeclineWhenCmsRollbackFails(RuntimeException failure, String code, String reason) {
        // Arrange
        var rollback = new RollbackRequest("123456789012", PAN, new BigDecimal("100"));
        doThrow(failure).when(cardManagementClient).rollback(rollback);

        // Act
        var result = service.rollback(rollback, REQUEST_TIME);

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.declineReason()).isEqualTo(reason);
        assertThat(result.rrn()).isEqualTo(rollback.rrn());
        verify(eventNotifier, never()).notify(new AuthServiceRollbackEvent(PAN));
    }

    @ParameterizedTest(name = "issued={0}")
    @ValueSource(ints = {0, 999, 1000})
    void shouldKeepNextRrnUniqueWhenSequenceReachesBlockBoundary(int issuedCount) {
        // Arrange
        when(limitUsageRepository.fetchRrnBlock()).thenReturn(1L, 2L);
        var previousRrns = issueRrns(issuedCount);

        // Act
        var rrn = service.generateRRN();

        // Assert
        assertThat(rrn).matches("\\d{12}");
        assertThat(previousRrns).doesNotHaveDuplicates().doesNotContain(rrn);
    }

    @Disabled("A-18: ТЗ требует 05/ISSUER_TIMEOUT, реализация возвращает 96/SERVICE_UNAVAILABLE")
    @Test
    void shouldReturnIssuerTimeoutWhenCmsIsUnavailableAccordingToSpecification() {
        // Arrange
        when(cardManagementClient.getCard(PAN)).thenThrow(new ServiceUnavailableException("unavailable"));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "05", "ISSUER_TIMEOUT");
    }

    @Disabled("P-14/P-20: по ТЗ лимиты имеют приоритет; реализация проверяет баланс раньше лимитов")
    @ParameterizedTest
    @CsvSource({"299, 599", "300, 599"})
    void shouldPreferLimitsWhenBothLimitsAndBalanceAreInsufficient(BigDecimal daily, BigDecimal monthly) {
        // Arrange
        when(cardManagementClient.getCard(PAN))
                .thenReturn(cardWithLimits(YearMonth.of(2026, 10), new BigDecimal("99"), daily, monthly));

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertDeclined(result, "61", "EXCEEDS_AMOUNT_LIMIT");
    }

    @Disabled("A-19: прямой authorize допускает сумму 0; положительность проверяет только Gateway")
    @Test
    void shouldRejectBeforeReserveWhenAmountIsZeroAccordingToSpecification() {
        // Arrange
        request = request(BigDecimal.ZERO, REQUEST_TIME, "POS", "5411");
        var card = activeCard();
        when(cardManagementClient.getCard(PAN)).thenReturn(card);
        allowLimits(card);

        // Act
        var result = service.authorize(request, REQUEST_TIME);

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        verify(cardManagementClient, never()).reserve(any(), anyString(), anyString());
    }

    static Stream<Arguments> lookupFailures() {
        return Stream.of(
                arguments(new CardNotFoundException("missing"), "14", "CARD_NOT_FOUND"),
                arguments(new InvalidGetCardRequestException("invalid"), "14", "CARD_NOT_FOUND"),
                arguments(new ServiceUnavailableException("unavailable"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new InternalCardManagerException("internal"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new GetCardException("failed"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new PaymentRequiredException("blocked"), "05", "CARD_BLOCKED"),
                arguments(new IllegalStateException("unexpected"), "05", "UNKNOWN_REASON"));
    }

    static Stream<Arguments> reservationFailures() {
        return Stream.of(
                arguments(new CardNotFoundException("missing"), "14", "CARD_NOT_FOUND"),
                arguments(new InvalidReserveRequestException("invalid"), "14", "CARD_NOT_FOUND"),
                arguments(new ServiceUnavailableException("unavailable"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new InternalCardManagerException("internal"), "96", "SERVICE_UNAVAILABLE"),
                arguments(new InsufficientFundsException("changed balance"), "51", "INSUFFICIENT_FUNDS"),
                arguments(new ReserveException("failed"), "96", "RESERVATION_FAILED"),
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

    static Stream<Optional<String>> issuerResults() {
        return Stream.of(Optional.of("ISS001"), Optional.empty());
    }

    private List<String> issueRrns(int count) {
        return IntStream.range(0, count).mapToObj(ignored -> service.generateRRN()).toList();
    }

    private void allowLimits(CardModel card) {
        LocalDate date = request.transmissionDateTime().atZone(ZoneOffset.UTC).toLocalDate();
        when(limitUsageRepository.upsertLimitUsage(PAN, date, request.amount(), card.dailyLimit(), card.monthlyLimit()))
                .thenReturn(1);
    }

    private void assertApproved(AuthorizationResponse result) {
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.declineReason()).isNull();
        assertThat(result.stan()).isEqualTo(request.stan());
        assertThat(result.rrn()).matches("\\d{12}");
        assertThat(result.authCode()).matches("[A-Z0-9]{6}");
        assertThat(result.processingTimeMs()).isEqualTo(12);
    }

    private void assertDeclined(AuthorizationResponse result, String code, String reason) {
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.declineReason()).isEqualTo(reason);
        assertThat(result.stan()).isEqualTo(request.stan());
        assertThat(result.rrn()).isNull();
        assertThat(result.authCode()).isNull();
        assertThat(result.processingTimeMs()).isEqualTo(12);
    }

    private CardModel activeCard() {
        return card(CardModelStatus.ACTIVE, YearMonth.of(2026, 10), new BigDecimal("1000"));
    }

    private CardModel card(CardModelStatus status, YearMonth expiry, BigDecimal balance) {
        return new CardModel(CARD_ID, PAN, "400000", "IVAN IVANOV", expiry, status, "643", new BigDecimal("1200"),
                new BigDecimal("1500"), balance, "ISS001", REQUEST_TIME);
    }

    private CardModel cardWithLimits(YearMonth expiry, BigDecimal balance, BigDecimal daily, BigDecimal monthly) {
        return new CardModel(CARD_ID, PAN, "400000", "IVAN IVANOV", expiry, CardModelStatus.ACTIVE, "643", daily,
                monthly, balance, "ISS001", REQUEST_TIME);
    }

    private AuthorizationRequest request(BigDecimal amount, Instant time, String terminalType, String mcc) {
        return new AuthorizationRequest("0100", "000001", PAN, "000000", amount, "643", time, "TERM0001",
                terminalType, "MERCH00000000001", mcc, "ACQ001", "ISS001");
    }
}
