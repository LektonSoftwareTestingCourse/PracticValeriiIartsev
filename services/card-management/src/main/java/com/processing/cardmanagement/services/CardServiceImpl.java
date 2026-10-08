package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.*;
import com.processing.cardmanagement.exceptions.*;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import jakarta.annotation.Nullable;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

@Slf4j
@RequiredArgsConstructor
public class CardServiceImpl implements CardService {

    private static final BigDecimal DEFAULT_DAILY_LIMIT = new BigDecimal("15000000");
    private static final BigDecimal DEFAULT_MONTHLY_LIMIT = new BigDecimal("300000000");
    private static final BigDecimal DEFAULT_INITIAL_BALANCE = new BigDecimal("100000000");

    private final CardRepository cardRepository;
    private final ReservationRepository reservationRepository;
    private final ReservationRollbackRepository reservationRollbackRepository;
    private final CardServiceSettings settings;
    private final CardServiceDefaults defaults;
    private final PanGenerator panGenerator;
    private final CardEventNotifier eventNotifier;
    private final BinIssuerService binIssuerService;
    private final RetryService retryService;
    private final TransactionRunner transactionRunner;

    @Override
    public Card createCard(
        String bin,
        String cardholderName,
        String currencyCode,
        BigDecimal dailyLimit,
        BigDecimal monthlyLimit,
        BigDecimal initialBalance
    ) {
        var draft = new CardDraft(
            bin,
            cardholderName,
            CardStatus.ACTIVE,
            currencyCode,
            dailyLimit != null ? dailyLimit : Objects.requireNonNullElse(defaults.dailyLimit(), DEFAULT_DAILY_LIMIT),
            monthlyLimit != null ? monthlyLimit : Objects.requireNonNullElse(defaults.monthlyLimit(), DEFAULT_MONTHLY_LIMIT),
            initialBalance != null ? initialBalance : Objects.requireNonNullElse(defaults.balance(), DEFAULT_INITIAL_BALANCE)
        );

        var issuerId = binIssuerService.getIssuerId(draft.bin());
        int validityPeriod = settings.cardValidityPeriod();
        Card savedCard = retryService.supply(
            settings.maxCardCreationRetries(),
            () -> cardRepository.create(Card.fromDraft(
                panGenerator.generatePan(bin),
                issuerId,
                validityPeriod,
                draft
            ))
        );
        eventNotifier.notifyListeners(new CardServiceCreationEvent(1));
        return savedCard;
    }

    // Выглядит страшно, но логика такая - пытаемся сначала сохранить ВСЕ карты
    // Если не выходит - добавляем поштучно с ретраем
    @Override
    public List<Card> createCards(List<CardDraft> data) {
        var cards = data
            .stream()
            .map(draft -> Card.fromDraft(
                panGenerator.generatePan(draft.bin()),
                binIssuerService.getIssuerId(draft.bin()),
                settings.cardValidityPeriod(),
                draft
            ))
            .toList();

        List<Card> saved;
        try {
            saved = cardRepository.createAll(cards);
        } catch (MassiveCardCreationCollisionException ex) {
            log.error(ex.getMessage(), ex);
            saved = transactionRunner.runSupplierWithNestedQuery(nestedTransactionRunner -> {
                var list = new ArrayList<Card>(cards.size());
                for (var card : cards) {
                    list.add(retryService.supply(
                        settings.maxCardCreationRetries(),
                        () -> nestedTransactionRunner.runSupplier(() ->
                            cardRepository.create(
                                card.copyWithPan(panGenerator.generatePan(card.bin()))
                            )
                        )
                    ));
                }
                return list;
            });
        }

        eventNotifier.notifyListeners(new CardServiceCreationEvent(saved.size()));
        return saved;
    }

    @Override
    public Card getCard(String pan) {
        return cardRepository
            .findByPan(pan)
            .orElseThrow(() -> new CardNotFoundException(pan));
    }

    @Override
    public List<Card> getCards(
        @Nullable Integer limit,
        @Nullable Long offset,
        @Nullable CardStatus status,
        @Nullable String bin,
        @Nullable String issuerId,
        @Nullable Instant startDate,
        @Nullable Instant endDate
    ) {
        if (limit != null && limit > settings.maxPageLimit()) {
            throw new TooLargeLimitException(limit, settings.maxPageLimit());
        }
        return cardRepository.findCards(
            limit != null ? limit : defaults.pageLimit(),
            offset != null ? offset : defaults.pageOffset(),
            status,
            bin,
            issuerId,
            startDate,
            endDate
        );
    }

    @Override
    public Card patchCard(
        String pan,
        @Nullable CardStatus status,
        @Nullable BigDecimal dailyLimit,
        @Nullable BigDecimal monthlyLimit,
        @Nullable BigDecimal availableBalance
    ) {
        return transactionRunner.runSupplier(() -> {
            var card = getCardForUpdate(pan);
            card = cardRepository.update(
                card.withData(
                    status != null ? status : card.status(),
                    dailyLimit != null ? dailyLimit : card.dailyLimit(),
                    monthlyLimit != null ? monthlyLimit : card.monthlyLimit(),
                    availableBalance != null ? availableBalance : card.availableBalance()
                )
            );
            eventNotifier.notifyListeners(new CardServicePatchEvent(pan));
            return card;
        });
    }

    @Override
    public void deleteCard(String pan) {
        transactionRunner.run(() -> {
            cardRepository.update(getCardForUpdate(pan).deleted());
            eventNotifier.notifyListeners(new CardServiceDeletionEvent(pan));
        });
    }

    @Override
    public long countAllCardsAndUpdate() {
        var value = cardRepository.countAllCards();
        eventNotifier.notifyListeners(new CardsAmountUpdatedEvent(value));
        return value;
    }

    @Override
    public long countCardsFiltered(
        @Nullable CardStatus status,
        @Nullable String bin,
        @Nullable String issuerId,
        @Nullable Instant startDate,
        @Nullable Instant endDate
    ) {
        return cardRepository.countCardsFiltered(
            status,
            bin,
            issuerId,
            startDate,
            endDate
        );
    }

    @Override
    public Card reserve(String pan, BigDecimal amount, String rrn) {
        return transactionRunner.runSupplier(() -> {
            var card = getCardForUpdate(pan);
            var reservation = card.startReservation(amount, rrn);
            if (!reservationRepository.isUnique(rrn, pan)) {
                throw new ReservationAlreadyExistsException(rrn, pan);
            }
            reservation = reservationRepository.save(reservation);
            card = cardRepository.update(card.withReservation(reservation));
            eventNotifier.notifyListeners(new CardServiceReserveEvent(pan, rrn, amount));
            return card;
        });
    }

    @Override
    public Card rollback(String pan, BigDecimal amount, String rrn) {
        return transactionRunner.runSupplier(() -> {
            var card = getCardForUpdate(pan);
            var reservation = reservationRepository
                .findByRrnAndPanForUpdate(rrn, pan)
                .orElseThrow(() -> new ReservationNotFoundException(rrn, pan));
            var rollback = reservationRollbackRepository.save(
                reservation.startRollback(amount)
            );
            reservationRepository.save(reservation.rolledBack(rollback));
            card = cardRepository.update(card.withRollback(rollback));
            eventNotifier.notifyListeners(new CardServiceRollbackEvent(pan, rrn, amount));
            return card;
        });
    }

    @Override
    public int bulkUpdateStatus(
        @Nullable List<String> bins,
        @Nullable List<String> pans,
        CardStatus status
    ) {

        if (bins == null && pans == null) {
            throw new IllegalArgumentException("Either bin or pans must be provided");
        }

        if (bins != null && pans != null) {
            throw new IllegalArgumentException("Only one of bin or pans must be provided");
        }

        return transactionRunner.runSupplier(() -> {
            List<Card> cards = List.of();

            if (bins != null) {
                cards = cardRepository.findCardsByBinsForUpdate(bins);
            }

            if (pans != null) {
                cards = cardRepository.findCardsByPansForUpdate(pans);
            }

            cards.forEach(card -> cardRepository.update(card.withData(
                status,
                card.dailyLimit(),
                card.monthlyLimit(),
                card.availableBalance()
            )));

            return cards.size();
        });
    }

    private Card getCardForUpdate(String pan) {
        return cardRepository
            .findByPanForUpdate(pan)
            .orElseThrow(() -> new CardNotFoundException(pan));
    }
}
