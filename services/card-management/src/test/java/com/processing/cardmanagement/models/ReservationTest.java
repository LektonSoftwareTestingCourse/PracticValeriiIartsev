package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.assertThat;

class ReservationTest {

    @Test
    void shouldMarkRolledBackWhenRollbackBelongsToReservation() {
        // Arrange
        var reservation = new Reservation("4000001234567899", new BigDecimal("100"), "123456789012");

        // Act
        var rollback = reservation.startRollback(new BigDecimal("100"));
        var updated = reservation.rolledBack(rollback);

        // Assert
        assertThat(rollback.reservationId()).isEqualTo(reservation.id());
        assertThat(rollback.pan()).isEqualTo(reservation.pan());
        assertThat(rollback.rrn()).isEqualTo(reservation.rrn());
        assertThat(rollback.rollbackAmount()).isEqualByComparingTo("100");
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

        // Act
        var error = catchThrowable(() -> reservation.rolledBack(another.startRollback(new BigDecimal("100"))));

        // Assert
        assertThat(error)
                .isInstanceOf(IllegalArgumentException.class);
    }
}
