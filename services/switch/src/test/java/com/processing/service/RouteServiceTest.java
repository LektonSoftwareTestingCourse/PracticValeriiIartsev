package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {

    @Mock
    private RoutingService routingService;
    @Mock
    private AuthorizationClient authorizationClient;
    @Mock
    private AcquiringFeeClient acquiringFeeClient;
    @Mock
    private LoggerClient loggerClient;
    @InjectMocks
    private RouteService service;

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
                Instant.parse("2026-09-24T12:00:00Z"),
                "TERM0001",
                "POS",
                "MERCH0000000001",
                "5411",
                "ACQ001",
                null
        );
    }

    @ParameterizedTest
    @MethodSource("rollbackResponses")
    void shouldRollbackAndDeclineWhenApprovedTransactionCannotBePublished(RollbackResponse rollbackResponse) {
        // Arrange
        var routed = request.withIssuerId("ISS001");
        when(routingService.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        when(authorizationClient.authorize(routed)).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(false);
        when(authorizationClient.rollback(routed, "123456789012")).thenReturn(rollbackResponse);

        // Act
        var result = service.route(request);

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("96");
        assertThat(result.stan()).isEqualTo(request.stan());
        var order = inOrder(loggerClient, authorizationClient);
        order.verify(loggerClient).log(any());
        order.verify(authorizationClient).rollback(routed, "123456789012");
    }

    @Test
    void shouldPublishTransactionBeforeReturningWhenAuthorizationIsApproved() {
        // Arrange
        var response = approvedResponse();
        var routed = request.withIssuerId("ISS001");
        var fee = new BigDecimal("1.50");
        when(routingService.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        when(authorizationClient.authorize(routed)).thenReturn(response);
        when(acquiringFeeClient.fetchAcquiringFee(request.transmissionDateTime(), request.stan(), request.pan(),
                request.terminalId(), request.amount())).thenReturn(fee);
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        var result = service.route(request);

        // Assert
        assertThat(result).isSameAs(response);
        var transaction = ArgumentCaptor.forClass(TransactionRequest.class);
        var order = inOrder(authorizationClient, loggerClient);
        order.verify(authorizationClient).authorize(routed);
        order.verify(loggerClient).log(transaction.capture());
        assertThat(transaction.getValue())
                .extracting(TransactionRequest::pan, TransactionRequest::stan, TransactionRequest::rrn,
                        TransactionRequest::issuerId, TransactionRequest::amount, TransactionRequest::acquiringFee,
                        TransactionRequest::status, TransactionRequest::processingTimeMs)
                .containsExactly(request.pan(), request.stan(), response.rrn(), "ISS001", request.amount(), fee,
                        TransactionStatus.APPROVED, 5);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @Test
    void shouldDeclineWithoutAuthorizationWhenBinIsUnknown() {
        // Arrange
        when(routingService.getIssuerIdByPan(request.pan())).thenThrow(new UnknownBinException("400000"));
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        var result = service.route(request);

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("14");
        verifyNoInteractions(authorizationClient, acquiringFeeClient);
        verify(loggerClient).log(any());
    }

    @Test
    void shouldDeclineWhenAuthorizationIsUnavailable() {
        // Arrange
        when(routingService.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        when(authorizationClient.authorize(request.withIssuerId("ISS001")))
                .thenThrow(new AuthorizationException(request.stan(), 3, "timeout"));

        // Act
        var result = service.route(request);

        // Assert
        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("05");
        verifyNoInteractions(acquiringFeeClient);
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void shouldNotRollbackWhenAuthorizationIsDeclined(boolean published) {
        // Arrange
        var response = new AuthorizationResponse("0110", request.stan(), null, null, "51", "DECLINED",
                "INSUFFICIENT_FUNDS", null);
        when(routingService.getIssuerIdByPan(request.pan())).thenReturn("ISS001");
        when(authorizationClient.authorize(request.withIssuerId("ISS001"))).thenReturn(response);
        when(loggerClient.log(any())).thenReturn(published);

        // Act
        var result = service.route(request);

        // Assert
        assertThat(result).isSameAs(response);
        var captor = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(loggerClient).log(captor.capture());
        assertThat(captor.getValue().status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(captor.getValue().declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(captor.getValue().processingTimeMs()).isZero();
        verify(authorizationClient, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @CsvSource(value = {
            "TERM1, TERM1000, SHOP, SHOP00000000000",
            "TERM000123, TERM0001, MERCH01234567890, MERCH1234567890",
            "null, null, null, null",
            "'', '', '', ''",
            "TERM0001, TERM0001, ABCDEFGHIJKLMNO, ABCDEFGHIJKLMNO",
            "TERM0001, TERM0001, ABCDEFGHIJKLMNOP, ABCDEFGHIJKLMNO",
            "TERM0001, TERM0001, MERCH123456789012, MERCH1234567890",
            "TERM0001, TERM0001, MERCH12345678901, MERCH1234567890"
    }, nullValues = "null")
    void shouldNormalizeIdentifiersWhenForwardingToAuthorization(String terminalId, String expectedTerminal,
                                                                String merchantId, String expectedMerchant) {
        // Arrange
        var input = new AuthorizationRequest(request.mti(), request.stan(), request.pan(), request.processingCode(),
                request.amount(), request.currencyCode(), request.transmissionDateTime(), terminalId, request.terminalType(),
                merchantId, request.mcc(), request.acquirerId(), null);
        when(routingService.getIssuerIdByPan(input.pan())).thenReturn("ISS001");
        when(authorizationClient.authorize(any())).thenReturn(approvedResponse());
        when(loggerClient.log(any())).thenReturn(true);

        // Act
        service.route(input);

        // Assert
        var captor = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorizationClient).authorize(captor.capture());
        assertThat(captor.getValue().terminalId()).isEqualTo(expectedTerminal);
        assertThat(captor.getValue().merchantId()).isEqualTo(expectedMerchant);
        assertThat(captor.getValue().issuerId()).isEqualTo("ISS001");
    }

    static Stream<RollbackResponse> rollbackResponses() {
        return Stream.of(null,
                new RollbackResponse("123456789012", "00", "APPROVED", null, 0),
                new RollbackResponse("123456789012", "96", "DECLINED", "SERVICE_UNAVAILABLE", 0));
    }

    private AuthorizationResponse approvedResponse() {
        return new AuthorizationResponse("0110", request.stan(), "123456789012", "ABC123", "00", "APPROVED", null, 5);
    }
}
