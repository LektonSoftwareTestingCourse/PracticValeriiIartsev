package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Answers;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import java.time.Instant;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class ReservationTest {
    private static final Instant FIXED_TIME = Instant.parse("2026-09-24T12:00:00Z");
    @Mock(answer = Answers.CALLS_REAL_METHODS)
    private MockedStatic<Instant> clock;

    @BeforeEach
    void setUp() {
        clock.when(Instant::now).thenReturn(FIXED_TIME);
    }

    @Test
    void shouldMarkRolledBackWhenRollbackBelongsToReservation() {
        // Arrange
        var reservation = new Reservation("4000001234567899", new BigDecimal("100"), "123456789012");

        var rollback = reservation.startRollback(new BigDecimal("100"));

        // Act
        var updated = reservation.rolledBack(rollback);

        // Assert
        assertThat(rollback.reservationId()).isEqualTo(reservation.id());
        assertThat(rollback.pan()).isEqualTo(reservation.pan());
        assertThat(rollback.rrn()).isEqualTo(reservation.rrn());
        assertThat(rollback.rollbackAmount()).isEqualByComparingTo("100");
        assertThat(updated.updatedAt()).isEqualTo(FIXED_TIME);
        assertThat(updated.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
    }

    @Test
    void shouldRejectWhenReservationIsAlreadyRolledBack() {
        // Arrange
        var reservation = new Reservation("4000001234567899", new BigDecimal("100"), "123456789012");
        var updated = reservation.rolledBack(reservation.startRollback(new BigDecimal("100")));

        // Act
        var error = catchThrowable(() -> updated.startRollback(new BigDecimal("100")));

        // Assert
        assertThat(error)
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    @Test
    void shouldRejectWhenRollbackBelongsToAnotherReservation() {
        // Arrange
        var reservation = new Reservation("4000001234567899", new BigDecimal("100"), "123456789012");
        var another = new Reservation("4000001234567899", new BigDecimal("100"), "123456789013");
        var unrelatedRollback = another.startRollback(new BigDecimal("100"));

        // Act
        var error = catchThrowable(() -> reservation.rolledBack(unrelatedRollback));

        // Assert
        assertThat(error)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
