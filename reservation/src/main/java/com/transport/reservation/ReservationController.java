package com.transport.reservation;

import com.transport.mobility.contracts.v1.QuoteContracts;
import com.transport.mobility.observability.Telemetry;
import com.transport.reservation.ReservationModel.*;
import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import static com.transport.reservation.ReservationStore.fail;

@RestController
class ReservationController {
    private final ReservationStore store;
    private final RemoteServices remote;
    private final Clock business;

    ReservationController(ReservationStore store, RemoteServices remote, Clock business) {
        this.store = store;
        this.remote = remote;
        this.business = business;
    }

    @PostMapping("/api/v1/reservations")
    ResponseEntity<View> create(@AuthenticationPrincipal Jwt jwt, @RequestHeader("Idempotency-Key") String key,
                                @RequestHeader(value="traceparent",required=false) String traceparent, @RequestBody Request request) {
        String owner = subject(jwt);
        var quoteId = request.devisId();
        validateKey(key);
        if (quoteId == null) {
            throw fail(HttpStatus.BAD_REQUEST,"DEVIS_REQUIRED");
        }
        String hash = quoteId.toString();
        var replay = store.replay(owner,"CREATE",key,hash);
        if (replay != null) {
            return accepted(replay);
        }
        var quote = remote.quote(quoteId,owner);
        if (quote == null || quote.price() == null || quote.price().itinerary().segments().isEmpty()) {
            throw fail(HttpStatus.SERVICE_UNAVAILABLE,"INVALID_QUOTE_RESPONSE");
        }
        var item = store.transaction(() -> {
            store.lockKey(owner,"CREATE",key);
            var existing = store.replay(owner,"CREATE",key,hash);
            if (existing != null) {
                return existing;
            }
            if (quote.status() == QuoteContracts.Status.EXPIRED || !quote.expiresAt().isAfter(Instant.now())) {
                throw fail(HttpStatus.GONE,"QUOTE_EXPIRED");
            }
            if (quote.status() != QuoteContracts.Status.ISSUED) {
                throw fail(HttpStatus.CONFLICT,"QUOTE_ALREADY_USED");
            }
            var created = store.create(owner,quoteId,quote.expiresAt(),quote.price().itinerary().segments().get(0).plannedDeparture().toInstant(), validTrace(java.util.Objects.requireNonNullElse(Telemetry.traceparent(), java.util.Objects.toString(traceparent, ""))));
            store.remember(owner,"CREATE",key,hash,created.id());
            return created;
        });
        return accepted(item);
    }

    @GetMapping("/api/v1/reservations/{id}")
    View get(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id) {
        return view(store.owned(id,subject(jwt),false));
    }

    @GetMapping("/api/v1/reservations")
    List<View> list(@AuthenticationPrincipal Jwt jwt,@RequestParam(defaultValue="0") int page,
                    @RequestParam(defaultValue="20") int size) {
        if (page<0 || size<1 || size>100) {
            throw fail(HttpStatus.BAD_REQUEST,"INVALID_PAGE");
        }
        return store.list(subject(jwt),page,size).stream().map(this::view).toList();
    }

    @PostMapping("/api/v1/reservations/{id}/cancel")
    ResponseEntity<View> cancel(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,
                                @RequestHeader("Idempotency-Key") String key) {
        String owner = subject(jwt);
        validateKey(key);
        var current = store.transaction(() -> {
            String hash = id.toString();
            store.lockKey(owner,"CANCEL",key);
            var replay = store.replay(owner,"CANCEL",key,hash);
            if (replay != null) {
                return replay;
            }
            var item = store.owned(id,owner,true);
            if (item.status() != Status.CANCELLED && item.cancelRequested() == null) {
                if (item.status() == Status.FAILED_COMPENSATED || !business.instant().isBefore(item.firstDeparture())) {
                    throw fail(HttpStatus.CONFLICT,"CANCELLATION_NOT_ALLOWED");
                }
                store.markCancel(item);
            }
            store.remember(owner,"CANCEL",key,hash,id);
            return store.find(id,false);
        });
        return accepted(current);
    }

    @GetMapping("/api/v1/admin/reservations/{id}/operations")
    List<Step> operations(@PathVariable UUID id) {
        if (store.find(id,false)==null) {
            throw fail(HttpStatus.NOT_FOUND,"RESERVATION_NOT_FOUND");
        }
        return store.steps(id);
    }

    @PostMapping("/api/v1/admin/reservations/{id}/hold-expiry-evidence")
    @ResponseStatus(HttpStatus.ACCEPTED)
    void recordEvidence(@AuthenticationPrincipal Jwt jwt,@PathVariable UUID id,@RequestBody Proof proof) {
        Instant now = Instant.now();
        if (proof.operationId()==null || proof.executionUpperBoundAt()==null || proof.executionUpperBoundAt().isAfter(now)
                || proof.evidence()==null || proof.evidence().isBlank() || proof.evidence().length()>2000) {
            throw fail(HttpStatus.BAD_REQUEST,"INVALID_EVIDENCE");
        }
        store.transaction(() -> {
            var item = store.find(id,true);
            if (item == null) {
                throw fail(HttpStatus.NOT_FOUND,"RESERVATION_NOT_FOUND");
            }
            var steps = store.steps(id);
            var hold = steps.stream().filter(s -> s.id().equals(proof.operationId()) && s.action()==Action.HOLD
                    && s.state()==State.UNKNOWN).findFirst().orElseThrow(() -> fail(HttpStatus.CONFLICT,"HOLD_NOT_UNKNOWN"));
            var line = store.lines(id).stream().filter(l -> l.id().equals(hold.line())).findFirst().orElseThrow();
            if ((item.status()!=Status.NEEDS_RECONCILIATION && item.status()!=Status.COMPENSATING)
                    || line.allocation().operator()==com.transport.mobility.contracts.v1.Offer.Operator.CAR
                    || SagaWorker.step(steps,hold.line(),Action.CONFIRM)!=null) {
                throw fail(HttpStatus.CONFLICT,"HOLD_EXPIRY_NOT_APPLICABLE");
            }
            if (proof.executionUpperBoundAt().isBefore(item.created())) {
                throw fail(HttpStatus.BAD_REQUEST,"INVALID_EXECUTION_BOUND");
            }
            store.saveProof(hold.id(),proof.executionUpperBoundAt(),proof.executionUpperBoundAt().plusSeconds(630),
                    proof.evidence(),subject(jwt));
            store.wake(id);
            return null;
        });
    }

    @GetMapping("/internal/v1/reservations/{id}/outcome")
    QuoteOutcome outcome(@PathVariable UUID id) {
        var item = store.find(id,false);
        if (item == null) {
            throw fail(HttpStatus.NOT_FOUND,"RESERVATION_NOT_FOUND");
        }
        return new QuoteOutcome(item.id(),item.quote(),item.status(),ReservationStore.quoteOutcome(item),
                item.everConfirmed(),item.failure(),item.version());
    }

    private ResponseEntity<View> accepted(Case item) {
        return ResponseEntity.accepted().location(URI.create("/api/v1/reservations/"+item.id())).body(view(item));
    }

    private View view(Case item) {
        var steps = store.steps(item.id());
        var lines = store.lines(item.id()).stream().map(line -> {
            var hold = SagaWorker.step(steps,line.id(),Action.HOLD);
            var confirm = SagaWorker.step(steps,line.id(),Action.CONFIRM);
            boolean released = steps.stream().anyMatch(s -> line.id().equals(s.line()) && s.action()==Action.CANCEL && s.state()==State.SUCCEEDED);
            String state = released ? "CANCELLED" : confirm != null ? visible(confirm,"CONFIRMED")
                    : hold != null ? visible(hold,"HELD") : "NEW";
            var price = item.snapshot() == null ? null : item.snapshot().lines().stream()
                    .filter(l -> line.id().equals(l.lineId())).findFirst().orElse(null);
            return new LineView(line.id(),line.segment(),line.allocation(),price,ref(hold),ref(confirm),state);
        }).toList();
        return new View(item.id(),item.quote(),item.status(),item.snapshot()==null ? null : item.snapshot().total(),
                lines,item.failure(),item.created(),item.version());
    }

    private static String ref(Step s) { return s != null && s.result()!=null ? s.result().reference() : null; }
    private static String visible(Step s,String success) { return s.state()==State.SUCCEEDED ? success : s.state().name(); }
    private static String validTrace(String value) {
        if (value == null || !value.matches("00-[0-9a-f]{32}-[0-9a-f]{16}-[0-9a-f]{2}")
                || value.substring(3,35).equals("0".repeat(32)) || value.substring(36,52).equals("0".repeat(16))) {
            return null;
        }
        return value;
    }

    private static void validateKey(String key) {
        if (key.isBlank() || key.length()>128) throw fail(HttpStatus.BAD_REQUEST,"INVALID_IDEMPOTENCY_KEY");
    }
    private static String subject(Jwt jwt) {
        String value = jwt.getSubject();
        if (value==null || value.isBlank() || value.length()>255) throw fail(HttpStatus.UNAUTHORIZED,"INVALID_SUBJECT");
        return value;
    }

    record Request(UUID devisId) {}
    record Proof(UUID operationId, Instant executionUpperBoundAt, String evidence) {}
    record QuoteOutcome(UUID reservationId,UUID devisId,Status reservationStatus,String quoteOutcome,
                        boolean everConfirmed,String failureCode,long aggregateVersion) {}
}
