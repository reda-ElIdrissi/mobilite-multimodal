package com.transport.mobility.operations;

import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.contracts.v1.OperationStatus.State;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

class OperationJournal {
    private final JdbcTemplate db;
    private final TransactionTemplate tx;
    private final JsonMapper json;
    private final Participant participant;

    OperationJournal(JdbcTemplate db, PlatformTransactionManager transactionManager, JsonMapper json, Participant participant) {
        this.db = db;
        this.tx = new TransactionTemplate(transactionManager);
        this.json = json;
        this.participant = participant;
    }

    OperationStatus hold(String caller, HoldCommand command) {
        if (command.offerId() == null || command.offerId().isBlank()
                || command.actorUserId() == null || command.actorUserId().isBlank()
                || command.actorUserId().length() > 255) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_HOLD");
        }
        return run(caller, command.operationId(), command.reservationLineId(), "HOLD", null, command,
                now -> participant.hold(command, now));
    }

    OperationStatus confirm(String caller, String ref, ConfirmCommand command) {
        return run(caller, command.operationId(), command.reservationLineId(), "CONFIRM", ref, command, now -> {
            checkReference(caller, command.reservationLineId(), "HOLD", ref);
            if (participant.remote() && relatedState(caller, command.reservationLineId(), "CANCEL", ref,
                    "'IN_FLIGHT','UNKNOWN','SUCCEEDED'")) {
                throw new Participant.Rejected("HOLD_CANCELLATION_STARTED");
            }
            return participant.confirm(ref, command);
        });
    }

    OperationStatus cancel(String caller, CancelCommand command) {
        if (command.referenceKind() == null || command.reference() == null || command.reference().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "INVALID_CANCELLATION");
        }
        return run(caller, command.operationId(), command.reservationLineId(), "CANCEL", command.reference(), command, now -> {
            checkReference(caller, command.reservationLineId(),
                    command.referenceKind() == CancelCommand.ReferenceKind.HOLD ? "HOLD" : "CONFIRM", command.reference());
            if (participant.remote() && command.referenceKind() == CancelCommand.ReferenceKind.HOLD) {
                if (relatedState(caller, command.reservationLineId(), "CONFIRM", command.reference(), "'SUCCEEDED'")) {
                    throw new Participant.Rejected("BOOKING_REFERENCE_REQUIRED");
                }
                if (relatedState(caller, command.reservationLineId(), "CONFIRM", command.reference(), "'IN_FLIGHT'")) {
                    throw new Participant.NotSent("CONFIRMATION_PENDING");
                }
            }
            return participant.cancel(command);
        });
    }

    OperationStatus status(String caller, UUID id) {
        return tx.execute(ignored -> {
            var existing = get(id, false);
            if (existing == null || !existing.caller().equals(caller)) {
                throw new ResponseStatusException(HttpStatus.NOT_FOUND, "OPERATION_NOT_FOUND");
            }
            return expire(existing).status();
        });
    }

    private OperationStatus run(String caller, UUID id, UUID line, String action, String reference,
            Object command, Function<Instant, Object> work) {
        if (id == null || line == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "OPERATION_AND_LINE_REQUIRED");
        }
        String hash = hash(json.writeValueAsString(List.of(action, reference == null ? "" : reference, command)));
        if (!participant.remote()) {
            return tx.execute(ignored -> {
                var start = reserve(caller, id, line, action, reference, hash);
                return start.attempt() == null ? start.status() : execute(id, action, start, work);
            });
        }
        var start = tx.execute(ignored -> reserve(caller, id, line, action, reference, hash));
        return start.attempt() == null ? start.status() : execute(id, action, start, work);
    }

    private Start reserve(String caller, UUID id, UUID line, String action, String reference, String hash) {
        UUID attempt = UUID.randomUUID();
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        int inserted = db.update("""
                INSERT INTO operator_operation
                (operation_id,caller,reservation_line_id,action,request_hash,target_ref,state,attempt_id,started_at,updated_at)
                VALUES (?,?,?,?,?,?,'IN_FLIGHT',?,?,?) ON CONFLICT DO NOTHING
                """, id, caller, line, action, hash, reference, attempt, Timestamp.from(now), Timestamp.from(now));
        if (inserted == 1) {
            return new Start(attempt, now, null);
        }
        var existing = get(id, true);
        if (existing == null) {
            return new Start(null, now, error(id, State.REJECTED, "LINE_OPERATION_CONFLICT", false));
        }
        if (!existing.caller().equals(caller) || !existing.hash().equals(hash)) {
            return new Start(null, now, error(id, State.REJECTED, "OPERATION_ID_CONFLICT", false));
        }
        existing = expire(existing);
        if (existing.status().state() == State.NOT_SENT
                || ("CANCEL".equals(action) && existing.status().state() == State.UNKNOWN)) {
            db.update("""
                    UPDATE operator_operation SET attempt_id=?, started_at=?, updated_at=?, state='IN_FLIGHT',
                        response_json=NULL,error_code=NULL WHERE operation_id=?
                    """, attempt, Timestamp.from(now), Timestamp.from(now), id);
            return new Start(attempt, now, null);
        }
        return new Start(null, now, existing.status());
    }

    private OperationStatus execute(UUID id, String action, Start start, Function<Instant, Object> work) {
        OperationStatus result;
        try {
            var value = work.apply(start.now());
            result = new OperationStatus(id, State.SUCCEEDED, value instanceof HoldReceipt hold ? hold : null,
                    value instanceof ConfirmationReceipt confirm ? confirm : null,
                    value instanceof CancellationReceipt cancel ? cancel : null, null);
        } catch (Participant.Rejected rejection) {
            result = error(id, State.REJECTED, rejection.getMessage(), false);
        } catch (Participant.NotSent notSent) {
            if (!participant.remote()) {
                throw notSent;
            }
            result = error(id, State.NOT_SENT, notSent.getMessage(), true);
        } catch (RuntimeException uncertain) {
            if (!participant.remote()) {
                throw uncertain;
            }
            result = error(id, State.UNKNOWN, "OPERATOR_OUTCOME_UNKNOWN", "CANCEL".equals(action));
        }
        OperationStatus finished = result;
        return tx.execute(ignored -> {
            save(finished, start.attempt());
            return get(id, false).status();
        });
    }

    private boolean relatedState(String caller, UUID line, String action, String reference, String states) {
        return Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM operator_operation WHERE caller=? AND reservation_line_id=?
                AND action=? AND target_ref=? AND state IN (
                """ + states + "))", Boolean.class, caller, line, action, reference));
    }

    private void checkReference(String caller, UUID line, String action, String reference) {
        if (!Boolean.TRUE.equals(db.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM operator_operation WHERE caller=? AND reservation_line_id=?
                AND action=? AND remote_ref=? AND state='SUCCEEDED')
                """, Boolean.class, caller, line, action, reference))) {
            throw new Participant.Rejected("REFERENCE_NOT_OWNED");
        }
    }

    private Row get(UUID id, boolean lock) {
        return db.query("SELECT * FROM operator_operation WHERE operation_id=?" + (lock ? " FOR UPDATE" : ""),
                (row, index) -> {
                    var jsonValue = row.getString("response_json");
                    var state = State.valueOf(row.getString("state"));
                    return new Row(row.getString("caller"), row.getString("request_hash"), row.getString("action"),
                            row.getObject("attempt_id", UUID.class), row.getTimestamp("started_at").toInstant(),
                            jsonValue == null ? new OperationStatus(id, state, null, null, null, null)
                                    : json.readValue(jsonValue, OperationStatus.class));
                }, id).stream().findFirst().orElse(null);
    }

    private Row expire(Row existing) {
        if (existing.status().state() == State.IN_FLIGHT && existing.startedAt().plusSeconds(30).isBefore(Instant.now())) {
            save(error(existing.status().operationId(), State.UNKNOWN, "STALE_OPERATION", "CANCEL".equals(existing.action())), existing.attempt());
            return get(existing.status().operationId(), false);
        }
        return existing;
    }

    private void save(OperationStatus result, UUID attempt) {
        String reference = result.hold() != null ? result.hold().holdId()
                : result.confirmation() != null ? result.confirmation().bookingRef() : null;
        db.update("""
                UPDATE operator_operation SET state=?,remote_ref=?,response_json=?::jsonb,error_code=?,updated_at=now()
                WHERE operation_id=? AND attempt_id=? AND state IN ('IN_FLIGHT','UNKNOWN')
                """, result.state().name(), reference, json.writeValueAsString(result),
                result.error() == null ? null : result.error().code(), result.operationId(), attempt);
    }

    private static OperationStatus error(UUID id, State state, String code, boolean retryable) {
        var outcome = state == State.REJECTED ? OperatorError.Outcome.REJECTED : OperatorError.Outcome.UNKNOWN;
        return new OperationStatus(id, state, null, null, null, new OperatorError(code, code, outcome, retryable, id.toString()));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record Row(String caller, String hash, String action, UUID attempt, Instant startedAt, OperationStatus status) {}
    private record Start(UUID attempt, Instant now, OperationStatus status) {}
}
