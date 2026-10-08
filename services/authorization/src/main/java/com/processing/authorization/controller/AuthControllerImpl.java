package com.processing.authorization.controller;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.authorization.services.AuthServiceImpl;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.authorization.RollbackResponse;
import jakarta.validation.Valid;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;

import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import static com.processing.authorization.constants.DeclineOutcome.*;

@Slf4j
@RestController
@RequestMapping("/api/internal")
@RequiredArgsConstructor
@Tag(name = "Authorization", description = "Endpoint for authorizing cards")
public class AuthControllerImpl implements AuthController {
    private final AuthServiceImpl authService;

    @PostMapping("/authorize")
    @Operation(summary = "Authorization", description = "Approves or declines card by pan")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Authorization success",
                    content = @Content(schema = @Schema(implementation = AuthorizationResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Incorrect request or unknown error",
                    content = @Content(schema = @Schema(implementation = AuthorizationResponse.class))),
            @ApiResponse(
                    responseCode = "403",
                    description = "Card blocked, inactive or expired",
                    content = @Content(schema = @Schema(implementation = AuthorizationResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Card not found",
                    content = @Content(schema = @Schema(implementation = AuthorizationResponse.class))),
            @ApiResponse(
                    responseCode = "422",
                    description = "Insufficient funds ",
                    content = @Content(schema = @Schema(implementation = AuthorizationResponse.class))),
            @ApiResponse(
                    responseCode = "503",
                    description = "Card manager unavailable",
                    content = @Content(schema = @Schema(implementation = AuthorizationResponse.class)))
    })
    public ResponseEntity<AuthorizationResponse> authorize(@Valid @RequestBody AuthorizationRequest request) {
        Instant requestInputTime = Instant.now();
        AuthorizationResponse response = authService.authorize(request, requestInputTime);

        boolean isApproved = response.status().equals(AuthorizationResponse.STATUS_APPROVED);
        HttpStatus httpStatus;
        if (isApproved) {
            httpStatus = HttpStatus.OK;
        } else {
            String declineReason = response.declineReason();
            httpStatus = switch (declineReason) {
                case REASON_CARD_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case REASON_SERVICE_UNAVAILABLE,
                     REASON_ISSUER_TIMEOUT,
                     REASON_RESERVATION_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
                case REASON_INSUFFICIENT_FUNDS -> HttpStatus.UNPROCESSABLE_ENTITY;
                case REASON_CARD_EXPIRED,
                     REASON_CARD_BLOCKED,
                     REASON_CARD_INACTIVE -> HttpStatus.FORBIDDEN;
                default -> HttpStatus.BAD_REQUEST;
            };
        }
        return ResponseEntity.status(httpStatus).body(response);
    }

    @PostMapping("/rollback")
    @Operation(summary = "Authorization", description = "Rollbacks transaction")
    @ApiResponses(value = {
            @ApiResponse(
                    responseCode = "200",
                    description = "Rollback success",
                    content = @Content(schema = @Schema(implementation = RollbackResponse.class))),
            @ApiResponse(
                    responseCode = "400",
                    description = "Incorrect request or unknown error",
                    content = @Content(schema = @Schema(implementation = RollbackResponse.class))),
            @ApiResponse(
                    responseCode = "404",
                    description = "Transaction not found",
                    content = @Content(schema = @Schema(implementation = RollbackResponse.class))),
            @ApiResponse(
                    responseCode = "409",
                    description = "Conflict: transaction already rolled back",
                    content = @Content(schema = @Schema(implementation = RollbackResponse.class))),
            @ApiResponse(
                    responseCode = "500",
                    description = "Internal server error: card management error",
                    content = @Content(schema = @Schema(implementation = RollbackResponse.class))),
            @ApiResponse(
                    responseCode = "503",
                    description = "Card management unavailable",
                    content = @Content(schema = @Schema(implementation = RollbackResponse.class)))
    })
    public ResponseEntity<RollbackResponse> rollback(@Valid @RequestBody RollbackRequest request) {
        Instant requestInputTime = Instant.now();
        RollbackResponse response = authService.rollback(request, requestInputTime);

        boolean isApproved = response.status().equals(RollbackResponse.STATUS_APPROVED);
        HttpStatus httpStatus;
        if (isApproved) {
            httpStatus = HttpStatus.OK;
        } else {
            String declineReason = response.declineReason();
            httpStatus = switch (declineReason) {
                case REASON_TRANSACTION_NOT_FOUND,
                     REASON_CARD_NOT_FOUND -> HttpStatus.NOT_FOUND;
                case REASON_ALREADY_ROLLED_BACK -> HttpStatus.CONFLICT;
                case REASON_SERVICE_UNAVAILABLE,
                     REASON_ROLLBACK_FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
                default -> HttpStatus.BAD_REQUEST;
            };
        }
        return ResponseEntity.status(httpStatus).body(response);
    }
}
