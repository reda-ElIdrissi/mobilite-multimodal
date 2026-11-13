package com.transport.reservation;

import com.transport.mobility.contracts.v1.QuoteContracts.Quote;
import com.transport.reservation.ReservationModel.*;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import com.transport.mobility.observability.Telemetry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientResponseException;

@Component
class SagaWorker {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(SagaWorker.class);
    private static final Duration BUDGET = Duration.ofMinutes(2);
    private final ReservationStore store;
    private final RemoteServices remote;
    private final Duration holdMargin;

    SagaWorker(ReservationStore store, RemoteServices remote,
               @Value("${mobility.reservation.hold-margin:PT30S}") Duration holdMargin) {
        this.store = store;
        this.remote = remote;
        if (holdMargin.isNegative() || holdMargin.isZero()) {
            throw new IllegalArgumentException("Invalid hold margin");
        }
        this.holdMargin = holdMargin;
    }

    @Scheduled(fixedDelayString = "${mobility.reservation.scan-ms:300}")
    void scan() {
        for (var id : store.due()) {
            Lease lease = store.acquire(id);
            if (lease != null) {
                tick(lease);
            }
        }
    }

    private void tick(Lease lease) {
        Instant next = Instant.now().plusSeconds(1);
        try {
            Plan plan = store.leased(lease, this::plan);
            if (plan == null) {
                return;
            }
            next = plan.next();
            if (plan.task() != null) {
                var task = plan.task();
                next = executeTraced(lease, task);
            }
        } catch (RuntimeException failed) {
            LOG.warn("Saga tick interrupted for reservation {}: {}", lease.id(), failed.toString());
            next = Instant.now().plusSeconds(2);
        } finally {
            store.release(lease, next);
        }
    }

    private Instant executeTraced(Lease lease, Task task) {
        var item = task.item();
        var operation = task.step();
        var span = Telemetry.resume("saga." + operation.action().name().toLowerCase(java.util.Locale.ROOT), item.traceparent(),
                        item.created().plus(BUDGET).isBefore(Instant.now()))
                .setAttribute("saga.id", lease.id().toString())
                .setAttribute("operation.id", operation.id().toString())
                .setAttribute("saga.work", task.work().name()).startSpan();
        try (var scope = span.makeCurrent();
             var sagaId = MDC.putCloseable("sagaId", lease.id().toString());
             var operationId = MDC.putCloseable("operationId", operation.id().toString())) {
            if (task.line() != null) {
                span.setAttribute("mobility.operator", task.line().allocation().operator().name());
            }
            var outcome = execute(task);
            span.setAttribute("mobility.result", outcome.result().state().name());
            LOG.info("Saga {} {} returned {}", operation.action(), task.work(), outcome.result().state());
            var finished = store.leased(lease, current -> complete(current, task, outcome));
            return finished == null ? Instant.now().plusSeconds(1) : finished;
        } finally {
            span.end();
        }
    }

    private Plan plan(Case item) {
        if (item.status() == Status.CANCELLED || item.status() == Status.FAILED_COMPENSATED) {
            return monitorReleased(item);
        }
        if (item.status() == Status.CONFIRMED && item.cancelRequested() == null) {
            return Plan.stop();
        }
        var steps = store.steps(item.id());
        Instant now = Instant.now();
        if (item.snapshot() == null) {
            var claim = step(steps, null, Action.CLAIM);
            if (claim == null && (item.cancelRequested() != null || !item.quoteExpires().isAfter(now))) {
                finishWithoutEngagement(item, item.cancelRequested() != null ? null : "QUOTE_EXPIRED");
                return Plan.stop();
            }
            if (claim != null && claim.attempts() >= 5 && claim.updated().plusSeconds(60).isAfter(now)) {
                store.transition(item, Status.NEEDS_RECONCILIATION, "CLAIM_UNRESOLVED");
                return new Plan(null, claim.updated().plusSeconds(60));
            }
            return send(item, null, Action.CLAIM, "", claim);
        }

        var lines = store.lines(item.id());
        if (item.cancelRequested() != null || item.status() == Status.COMPENSATING
                || item.status() == Status.CANCELLING || item.status() == Status.NEEDS_RECONCILIATION) {
            return compensate(item, lines, steps, now);
        }

        // A prepared command remains eligible for journal lookup after restart and past the deadline.
        for (var line : lines) {
            for (Action action : List.of(Action.HOLD, Action.CONFIRM)) {
                var operation = step(steps, line.id(), action);
                if (operation != null && operation.state() == State.IN_FLIGHT) {
                    return new Plan(new Task(item, line, operation, Work.LOOKUP), now);
                }
            }
        }
        if (!item.deadline().isAfter(now)) {
            store.transition(item, Status.COMPENSATING, "SAGA_DEADLINE");
            return Plan.again();
        }
        for (var line : lines) {
            var validation = step(steps, line.id(), Action.VALIDATE);
            if (validation == null || validation.state() != State.SUCCEEDED) {
                return send(item, line, Action.VALIDATE, "", validation);
            }
        }
        for (var line : lines) {
            var hold = step(steps, line.id(), Action.HOLD);
            if (hold == null || hold.state() == State.NOT_SENT) {
                return send(item, line, Action.HOLD, "", hold);
            }
            if (hold.state() != State.SUCCEEDED) {
                store.transition(item, Status.COMPENSATING, code(hold, "HOLD_FAILED"));
                return Plan.again();
            }
        }
        if (item.status() != Status.CONFIRMING) {
            store.transition(item, Status.CONFIRMING, null);
            return Plan.again();
        }
        for (var line : lines) {
            var confirm = step(steps, line.id(), Action.CONFIRM);
            if ((confirm == null || confirm.state() == State.NOT_SENT)
                    && !step(steps,line.id(),Action.HOLD).result().expires().isAfter(now.plus(holdMargin))) {
                store.transition(item, Status.COMPENSATING, "HOLD_MARGIN_EXHAUSTED");
                return Plan.again();
            }
        }
        for (var line : lines) {
            var confirm = step(steps, line.id(), Action.CONFIRM);
            if (confirm == null || confirm.state() == State.NOT_SENT) {
                return send(item, line, Action.CONFIRM, step(steps,line.id(),Action.HOLD).result().reference(), confirm);
            }
            if (confirm.state() != State.SUCCEEDED) {
                store.transition(item, Status.COMPENSATING, code(confirm,"CONFIRM_FAILED"));
                return Plan.again();
            }
        }
        store.transition(item, Status.CONFIRMED, null);
        return Plan.stop();
    }

    private Plan compensate(Case item, List<ReservationModel.Line> lines, List<Step> steps, Instant now) {
        boolean unresolved = false;
        Task lookup = null;
        Instant recheck = null;
        for (int index = lines.size()-1; index >= 0; index--) {
            var line = lines.get(index);
            var hold = step(steps, line.id(), Action.HOLD);
            var confirm = step(steps, line.id(), Action.CONFIRM);
            Step uncertain = ambiguous(confirm) ? confirm : ambiguous(hold) ? hold : null;
            if (uncertain != null) {
                unresolved = true;
                var proof = uncertain.action() == Action.HOLD && confirm == null ? store.evidence(uncertain.id()) : null;
                Instant due = uncertain.updated().plusSeconds(uncertain.state() == State.IN_FLIGHT ? 2 : 60);
                if (proof != null && proof.resolvedAt() == null && !proof.safeExpiry().isAfter(now)
                        && !uncertain.updated().plusSeconds(2).isAfter(now) && lookup == null) {
                    lookup = new Task(item,line,uncertain,Work.PURGE);
                } else if (lookup == null && !due.isAfter(now)) {
                    lookup = new Task(item,line,uncertain,Work.LOOKUP);
                }
                recheck = earlier(recheck, proof != null && proof.safeExpiry().isAfter(now) ? earlier(due,proof.safeExpiry()) : due);
                continue;
            }
            String reference = succeeded(confirm) ? "BOOKING:" + confirm.result().reference()
                    : succeeded(hold) ? "HOLD:" + hold.result().reference() : null;
            if (reference == null) {
                continue;
            }
            var cancel = steps.stream().filter(s -> s.action() == Action.CANCEL && line.id().equals(s.line())
                        && reference.equals(s.reference())).findFirst().orElse(null);
            if (succeeded(cancel)) {
                continue;
            }
            if (cancel == null) {
                return send(item,line,Action.CANCEL,reference,null);
            }
            unresolved = true;
            Instant due = cancel.updated().plusSeconds(cancel.attempts() >= 5 || cancel.state() == State.REJECTED
                    ? 60 : Math.min(16, 1L << cancel.attempts()));
            if (!due.isAfter(now) && cancel.attempts() < 5 && cancel.state() != State.REJECTED
                    && cancel.state() != State.IN_FLIGHT) {
                return send(item,line,Action.CANCEL,reference,cancel);
            }
            if (!due.isAfter(now) && lookup == null) {
                lookup = new Task(item,line,cancel,Work.LOOKUP);
            }
            recheck = earlier(recheck,due);
        }
        if (!unresolved) {
            finishWithoutEngagement(item,item.failure());
            return steps.stream().anyMatch(s -> s.state()==State.RELEASED) ? Plan.again() : Plan.stop();
        }
        if (lookup != null) {
            return new Plan(lookup,now);
        }
        boolean retryingCancel = steps.stream().anyMatch(s -> s.action()==Action.CANCEL && s.attempts()<5
                && s.state()!=State.SUCCEEDED && s.state()!=State.REJECTED);
        if (!retryingCancel) {
            store.transition(item,Status.NEEDS_RECONCILIATION,item.failure() == null ? "OPERATOR_OUTCOME_UNKNOWN" : item.failure());
        }
        return new Plan(null, recheck == null || recheck.isBefore(now) ? now.plusSeconds(1) : recheck);
    }

    private Plan monitorReleased(Case item) {
        var released = store.steps(item.id()).stream().filter(s -> s.state()==State.RELEASED)
                .min(java.util.Comparator.comparing(Step::updated)).orElse(null);
        if (released == null) return Plan.stop();
        var next = released.updated().plusSeconds(60);
        if (next.isAfter(Instant.now())) return new Plan(null,next);
        var line = store.lines(item.id()).stream().filter(l -> l.id().equals(released.line())).findFirst().orElseThrow();
        return new Plan(new Task(item,line,released,Work.MONITOR),Instant.now());
    }

    private Outcome execute(Task task) {
        if (task.step().action() == Action.CLAIM) {
            try {
                Quote quote = remote.claim(task.item());
                if (quote != null && task.item().id().equals(quote.reservationId()) && quote.price() != null
                        && task.item().quote().equals(quote.id())) {
                    return new Outcome(Result.success(null,null),quote);
                }
            } catch (RestClientResponseException failure) {
                int status = failure.getStatusCode().value();
                if (status == 410 || status == 409 || status == 404) {
                    return new Outcome(Result.state(State.REJECTED,"QUOTE_CLAIM_REJECTED"),null);
                }
            } catch (RuntimeException ignored) {
                // The same reservation id can safely replay the claim.
            }
            return new Outcome(Result.state(State.UNKNOWN,"QUOTE_CLAIM_UNAVAILABLE"),null);
        }
        Result result = switch (task.work()) {
            case SEND -> remote.send(task.item(),task.line(),task.step());
            case LOOKUP, MONITOR -> remote.lookup(task.line(),task.step());
            case VALIDATE -> remote.validate(task.item(),task.line());
            case PURGE -> {
                Result journal = remote.lookup(task.line(),task.step());
                if (journal.state() == State.SUCCEEDED || journal.state() == State.REJECTED
                        || (journal.state() == State.NOT_SENT && !"JOURNAL_ABSENT".equals(journal.code()))) {
                    yield journal;
                }
                yield journal.state() != State.IN_FLIGHT && remote.purge(task.item(), task.line())
                        ? Result.state(State.RELEASED,"HOLD_EXPIRED_PROVEN")
                        : Result.state(State.UNKNOWN,"PURGE_NOT_CONFIRMED");
            }
        };
        return new Outcome(result,null);
    }

    private Instant complete(Case item, Task task, Outcome outcome) {
        Step operation = task.step();
        Result result = outcome.result();
        Instant now = Instant.now();
        if (operation.action() == Action.CLAIM) {
            store.finish(item,operation,result);
            if (outcome.quote() != null) {
                store.importQuote(item,outcome.quote(),now.plus(BUDGET));
                if (item.cancelRequested() != null || !item.quoteExpires().isAfter(now)) {
                    store.transition(store.find(item.id(),false),Status.COMPENSATING,
                            item.cancelRequested() != null ? null : "QUOTE_START_DEADLINE");
                }
                return now;
            }
            if (result.state() == State.REJECTED) {
                finishWithoutEngagement(item,result.code());
                return null;
            }
            return now.plusSeconds(operation.attempts() >= 5 ? 60 : 2);
        }
        if (task.work() == Work.MONITOR) {
            if (result.state()==State.SUCCEEDED) {
                store.finish(item,operation,result);
                store.transition(item,Status.NEEDS_RECONCILIATION,"LATE_HOLD_AFTER_EXPIRY_PROOF");
                return now;
            }
            store.finish(item,operation,operation.result());
            return now;
        }
        boolean progressing = item.cancelRequested() == null && (item.status() == Status.HOLDING || item.status() == Status.CONFIRMING)
                && item.deadline().isAfter(now);
        if (task.work() == Work.SEND && result.state() == State.UNKNOWN) {
            result = Result.state(State.IN_FLIGHT,"OPERATOR_LOOKUP_REQUIRED");
        }
        if (task.work() == Work.LOOKUP && result.state() == State.NOT_SENT && !progressing
                && "JOURNAL_ABSENT".equals(result.code()) && operation.action() != Action.CANCEL) {
            result = Result.state(State.UNKNOWN,"JOURNAL_ABSENCE_NOT_PROOF");
        }
        store.finish(item,operation,result);
        if (result.state() == State.RELEASED) {
            store.resolveProof(operation.id());
        }
        if (progressing && operation.action() != Action.CANCEL && (result.state() == State.REJECTED
                || result.state() == State.UNKNOWN && operation.action() != Action.VALIDATE)) {
            store.transition(item,Status.COMPENSATING,result.code());
        }
        if (result.state() == State.IN_FLIGHT || result.state() == State.NOT_SENT
                || result.state() == State.UNKNOWN && (operation.action() == Action.CANCEL || operation.action() == Action.VALIDATE)) {
            return now.plusSeconds(1);
        }
        return now;
    }

    private Plan send(Case item, ReservationModel.Line line, Action action, String ref, Step prior) {
        var next = store.start(item.id(),line == null ? null : line.id(),action,ref,prior);
        return new Plan(new Task(item,line,next,action == Action.VALIDATE ? Work.VALIDATE : Work.SEND),Instant.now());
    }

    private void finishWithoutEngagement(Case item,String failure) {
        boolean cancel = item.cancelRequested() != null;
        store.transition(item,cancel ? Status.CANCELLED : Status.FAILED_COMPENSATED,cancel ? null : failure);
    }

    static Step step(List<Step> steps, java.util.UUID line, Action action) {
        return steps.stream().filter(s -> s.action() == action && Objects.equals(line,s.line())).findFirst().orElse(null);
    }
    private static boolean succeeded(Step step) { return step != null && step.state() == State.SUCCEEDED; }
    private static boolean ambiguous(Step step) {
        return step != null && (step.state() == State.IN_FLIGHT || step.state() == State.UNKNOWN
                || step.state() == State.NOT_SENT && "JOURNAL_ABSENT".equals(code(step,"")));
    }
    private static String code(Step step,String fallback) { return step.result() == null || step.result().code() == null ? fallback : step.result().code(); }
    private static Instant earlier(Instant a,Instant b) { return a == null || b.isBefore(a) ? b : a; }

    private enum Work { SEND, LOOKUP, VALIDATE, PURGE, MONITOR }
    private record Task(Case item,ReservationModel.Line line,Step step,Work work) {}
    private record Outcome(Result result,Quote quote) {}
    private record Plan(Task task,Instant next) {
        static Plan again() { return new Plan(null,Instant.now()); }
        static Plan stop() { return new Plan(null,null); }
    }
}
