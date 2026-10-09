package com.processing.authorization.constants;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;

import java.time.Instant;

/**
 * Исход отклонения транзакции с соответствующим кодом ответа.
 * <p>
 * Используется для унификации причин отказа при авторизации и откате.
 * Содержит предопределённые константы для всех бизнес-сценариев и технических
 * ошибок.
 * <p>
 * Каждый исход состоит из:
 * <ul>
 * <li><b>reason</b> — текстовый идентификатор причины (для логирования и
 * отладки)</li>
 * <li><b>code</b> — двузначный ISO-код ответа (для внешнего интерфейса)</li>
 * </ul>
 *
 * @param reason причина отказа
 * @param code статус код
 *
 * @see AuthorizationResponse
 * @see RollbackResponse
 */
public record DeclineOutcome(String reason, String code) {
    public static final String REASON_CARD_NOT_FOUND = "CARD_NOT_FOUND";

    public static final String REASON_SERVICE_UNAVAILABLE = "SERVICE_UNAVAILABLE";

    public static final String REASON_ISSUER_TIMEOUT = "ISSUER_TIMEOUT";

    public static final String REASON_UNKNOWN_REASON = "UNKNOWN_REASON";

    public static final String REASON_CARD_EXPIRED = "CARD_EXPIRED";

    public static final String REASON_CARD_BLOCKED = "CARD_BLOCKED";

    public static final String REASON_CARD_INACTIVE = "CARD_INACTIVE";

    public static final String REASON_EXCEEDS_AMOUNT_LIMIT = "EXCEEDS_AMOUNT_LIMIT";

    public static final String REASON_INSUFFICIENT_FUNDS = "INSUFFICIENT_FUNDS";

    public static final String REASON_RESERVATION_FAILED = "RESERVATION_FAILED";

    public static final String REASON_TRANSACTION_NOT_FOUND = "TRANSACTION_NOT_FOUND";

    public static final String REASON_ALREADY_ROLLED_BACK = "ALREADY_ROLLED_BACK";

    public static final String REASON_ROLLBACK_FAILED = "ROLLBACK_FAILED";

    public static final String REASON_DB_UNAVAILABLE = "DB_UNAVAILABLE";

    public static final DeclineOutcome CARD_NOT_FOUND = new DeclineOutcome(
            REASON_CARD_NOT_FOUND,
            AuthorizationResponse.CODE_CARD_NOT_FOUND);

    public static final DeclineOutcome SERVICE_UNAVAILABLE = new DeclineOutcome(
            REASON_SERVICE_UNAVAILABLE,
            AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);

    public static final DeclineOutcome ISSUER_TIMEOUT = new DeclineOutcome(
            REASON_ISSUER_TIMEOUT,
            AuthorizationResponse.CODE_DECLINED_GENERAL);

    public static final DeclineOutcome UNKNOWN_REASON = new DeclineOutcome(
            REASON_UNKNOWN_REASON,
            AuthorizationResponse.CODE_DECLINED_GENERAL);

    public static final DeclineOutcome CARD_EXPIRED = new DeclineOutcome(
            REASON_CARD_EXPIRED,
            AuthorizationResponse.CODE_CARD_EXPIRED);

    public static final DeclineOutcome CARD_BLOCKED = new DeclineOutcome(
            REASON_CARD_BLOCKED,
            AuthorizationResponse.CODE_DECLINED_GENERAL);

    public static final DeclineOutcome CARD_INACTIVE = new DeclineOutcome(
            REASON_CARD_INACTIVE,
            AuthorizationResponse.CODE_DECLINED_GENERAL);

    public static final DeclineOutcome EXCEEDS_AMOUNT_LIMIT = new DeclineOutcome(
            REASON_EXCEEDS_AMOUNT_LIMIT,
            AuthorizationResponse.CODE_EXCEEDS_LIMIT);

    public static final DeclineOutcome INSUFFICIENT_FUNDS = new DeclineOutcome(
            REASON_INSUFFICIENT_FUNDS,
            AuthorizationResponse.CODE_INSUFFICIENT_FUNDS);

    public static final DeclineOutcome RESERVATION_FAILED = new DeclineOutcome(
            REASON_RESERVATION_FAILED,
            AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);

    public static final DeclineOutcome TRANSACTION_NOT_FOUND = new DeclineOutcome(
            REASON_TRANSACTION_NOT_FOUND,
            RollbackResponse.CODE_TRANSACTION_NOT_FOUND);

    public static final DeclineOutcome ALREADY_ROLLED_BACK = new DeclineOutcome(
            REASON_ALREADY_ROLLED_BACK,
            RollbackResponse.CODE_DECLINED_GENERAL);

    public static final DeclineOutcome ROLLBACK_FAILED = new DeclineOutcome(
            REASON_ROLLBACK_FAILED,
            RollbackResponse.CODE_SERVICE_UNAVAILABLE);

    public static final DeclineOutcome DB_UNAVAILABLE = new DeclineOutcome(
            REASON_DB_UNAVAILABLE,
            AuthorizationResponse.CODE_SERVICE_UNAVAILABLE);

    public AuthorizationResponse buildAuthorization(AuthorizationRequest request, Instant requestInputTime) {
        return AuthorizationResponse.declined(request, reason, code, requestInputTime);
    }

    public RollbackResponse buildRollback(RollbackRequest request, Instant requestInputTime) {
        return RollbackResponse.declined(request, reason, code, requestInputTime);
    }
}
