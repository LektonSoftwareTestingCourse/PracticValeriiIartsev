package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockStatic;

class CardTest {

    @DisplayName("C-05: обновление модели")
    @ParameterizedTest
    @CsvSource({"0, 0", "99, 100", "100, 100"})
    void shouldUpdateWhenLimitsAreNonNegativeAndOrdered(BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        // Arrange
        var card = card(CardStatus.ACTIVE);

        // Act
        var updated = card.withData(CardStatus.BLOCKED, dailyLimit, monthlyLimit, new BigDecimal("50"));

        // Assert
        assertThat(updated.dailyLimit()).isEqualByComparingTo(dailyLimit);
        assertThat(updated.monthlyLimit()).isEqualByComparingTo(monthlyLimit);
        assertThat(updated.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.availableBalance()).isEqualByComparingTo("50");
        assertThat(card.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @DisplayName("C-06: мягкое удаление")
    @Test
    void shouldMarkDeletedWhenCardIsNotDeleted() {
        // Arrange
        var card = card(CardStatus.ACTIVE);

        // Act
        var deleted = card.deleted();

        // Assert
        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
        assertThat(deleted.availableBalance()).isEqualByComparingTo(card.availableBalance());
    }

    @DisplayName("C-06: повторное удаление")
    @Test
    void shouldRejectDeletionWhenCardIsAlreadyDeleted() {
        // Arrange
        var deleted = card(CardStatus.DELETED);

        // Act
        var error = catchThrowable(deleted::deleted);

        // Assert
        assertThat(error).isInstanceOf(IllegalStateException.class);
    }

    @DisplayName("C-10, C-15: срок +3 года, включая смену года")
    @ParameterizedTest
    @CsvSource({"2026-12, 2029-12", "2027-01, 2030-01"})
    void shouldAddThreeYearsWhenCreatingCardAtYearBoundary(YearMonth currentMonth, YearMonth expectedExpiry) {
        // Arrange
        var draft = new CardDraft("400000", "IVAN IVANOV", CardStatus.ACTIVE, "643",
                new BigDecimal("1200"), new BigDecimal("1500"), new BigDecimal("1000"));
        try (var clock = mockStatic(YearMonth.class)) {
            clock.when(YearMonth::now).thenReturn(currentMonth);

            // Act
            var card = Card.fromDraft("4000001234567899", "ISS001", 3, draft);

            // Assert
            assertThat(card.expiryDate()).isEqualTo(expectedExpiry);
            assertThat(card.id()).isNotNull();
            assertThat(card.pan()).isEqualTo("4000001234567899");
            assertThat(card.issuerId()).isEqualTo("ISS001");
            assertThat(card.bin()).isEqualTo(draft.bin());
            assertThat(card.cardholderName()).isEqualTo(draft.cardholderName());
            assertThat(card.status()).isEqualTo(draft.status());
            assertThat(card.currencyCode()).isEqualTo(draft.currencyCode());
            assertThat(card.dailyLimit()).isEqualByComparingTo(draft.dailyLimit());
            assertThat(card.monthlyLimit()).isEqualByComparingTo(draft.monthlyLimit());
            assertThat(card.availableBalance()).isEqualByComparingTo(draft.initialBalance());
        }
    }

    @DisplayName("C-13: резерв равен балансу")
    @Test
    void shouldDecreaseBalanceWhenReservationFitsBalance() {
        // Arrange
        var amount = new BigDecimal("100");
        var expectedBalance = BigDecimal.ZERO;
        var card = card(CardStatus.ACTIVE);

        // Act
        var reservation = card.startReservation(amount, "123456789012");
        var updated = card.withReservation(reservation);

        // Assert
        assertThat(reservation.pan()).isEqualTo(card.pan());
        assertThat(reservation.rrn()).isEqualTo("123456789012");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(updated.availableBalance()).isEqualByComparingTo(expectedBalance);
        assertThat(updated.id()).isEqualTo(card.id());
        assertThat(card.availableBalance()).isEqualByComparingTo("100");
    }

    @DisplayName("C-14: превышение баланса")
    @Test
    void shouldRejectWhenReservationExceedsBalance() {
        // Arrange
        var card = card(CardStatus.ACTIVE);

        // Act
        var error = catchThrowable(() -> card.startReservation(new BigDecimal("101"), "123456789012"));

        // Assert
        assertThat(error)
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(card.availableBalance()).isEqualByComparingTo("100");
    }

    @DisplayName("C-16: резерв меньше баланса")
    @Test
    void shouldLeaveOneKopeckWhenReservationIsBelowBalance() {
        // Arrange
        var amount = new BigDecimal("99");
        var expectedBalance = BigDecimal.ONE;
        var card = card(CardStatus.ACTIVE);

        // Act
        var reservation = card.startReservation(amount, "123456789012");
        var updated = card.withReservation(reservation);

        // Assert
        assertThat(reservation.pan()).isEqualTo(card.pan());
        assertThat(reservation.rrn()).isEqualTo("123456789012");
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(updated.availableBalance()).isEqualByComparingTo(expectedBalance);
        assertThat(updated.id()).isEqualTo(card.id());
        assertThat(card.availableBalance()).isEqualByComparingTo("100");
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, mode = EnumSource.Mode.EXCLUDE, names = "ACTIVE")
    void shouldRejectReservationWhenCardIsNotActive(CardStatus status) {
        // Arrange
        var card = card(status);

        // Act
        var error = catchThrowable(() -> card.startReservation(BigDecimal.ONE, "123456789012"));

        // Assert
        assertThat(error)
                .isInstanceOf(IllegalStateException.class);
        assertThat(card.availableBalance()).isEqualByComparingTo("100");
    }

    @Test
    void shouldRestoreBalanceWhenReservationIsRolledBack() {
        // Arrange
        var card = card(CardStatus.ACTIVE);
        var reservation = card.startReservation(new BigDecimal("99"), "123456789012");
        var reservedCard = card.withReservation(reservation);

        // Act
        var restored = reservedCard.withRollback(reservation.startRollback(new BigDecimal("99")));

        // Assert
        assertThat(restored).isEqualTo(card);
        assertThat(reservedCard.availableBalance()).isEqualByComparingTo("1");
    }

    @Test
    void shouldRejectWhenReservationBelongsToAnotherCard() {
        // Arrange
        var card = card(CardStatus.ACTIVE);
        var reservation = new Reservation("4000011234567898", BigDecimal.ONE, "123456789012");

        // Act
        var error1 = catchThrowable(() -> card.withReservation(reservation));
        var error2 = catchThrowable(() -> card.withRollback(reservation.startRollback(BigDecimal.ONE)));

        // Assert
        assertThat(error1).isInstanceOf(IllegalArgumentException.class);
        assertThat(error2)
                .isInstanceOf(IllegalArgumentException.class);

    }

    @ParameterizedTest
    @CsvSource({"-1, 100", "100, -1", "101, 100"})
    void shouldRejectWhenLimitsAreNegativeOrDailyExceedsMonthly(BigDecimal dailyLimit, BigDecimal monthlyLimit) {
        // Arrange
        var card = card(CardStatus.ACTIVE);

        // Act
        var error = catchThrowable(() -> card.withData(card.status(), dailyLimit, monthlyLimit, card.availableBalance()));

        // Assert
        assertThat(error)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private Card card(CardStatus status) {
        return new Card(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "4000001234567899",
                "400000",
                "IVAN IVANOV",
                YearMonth.of(2029, 9),
                status,
                "643",
                new BigDecimal("1000"),
                new BigDecimal("10000"),
                new BigDecimal("100"),
                "ISS001",
                Instant.parse("2026-09-24T12:00:00Z")
        );
    }
}
