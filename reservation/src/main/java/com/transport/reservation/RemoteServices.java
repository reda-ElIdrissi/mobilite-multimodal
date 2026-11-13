package com.transport.reservation;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.transport.mobility.contracts.v1.*;
import com.transport.mobility.contracts.v1.QuoteContracts.Claim;
import com.transport.mobility.contracts.v1.QuoteContracts.Quote;
import com.transport.reservation.ReservationModel.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;

@Component
class RemoteServices {
    private static final org.slf4j.Logger LOG = org.slf4j.LoggerFactory.getLogger(RemoteServices.class);
    private static final ParameterizedTypeReference<List<Offer>> OFFERS = new ParameterizedTypeReference<>() {};
    private final RestClient http;
    private RestClient carConfirm;
    private DemoHoldBarrier barrier;
    private final JsonMapper json;
    private final String pricing;
    private final String tokenUrl;
    private final String secret;
    private final Map<Offer.Operator, String> operators;
    private String accessToken;
    private Instant refreshAt = Instant.EPOCH;

    RemoteServices(@Value("${mobility.reservation.pricing-url}") String pricing,
                   @Value("${mobility.reservation.token-url}") String tokenUrl,
                   @Value("${mobility.reservation.secret}") String secret,
                   @Value("${mobility.reservation.rail-url}") String rail,
                   @Value("${mobility.reservation.air-url}") String air,
                   @Value("${mobility.reservation.car-url}") String car) {
        this.pricing = pricing;
        this.tokenUrl = tokenUrl;
        this.secret = secret;
        this.operators = Map.of(Offer.Operator.RAIL, rail, Offer.Operator.AIR, air, Offer.Operator.CAR, car);
        this.json = JsonMapper.builder().disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build();
        this.http = client(Duration.ofSeconds(6));
        this.carConfirm = http;
    }

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    void setDemoBarrier(DemoHoldBarrier barrier) {
        this.barrier = barrier;
    }

    @Component
    @org.springframework.context.annotation.Profile("demo & demo-expiry")
    static class DemoTimeout {
        DemoTimeout(RemoteServices remote, @Value("${mobility.demo.car-confirm-timeout:PT45S}") Duration timeout,
                    @Value("${mobility.reservation.lease:PT30S}") Duration lease) {
            if (timeout.compareTo(Duration.ofSeconds(40)) < 0 || timeout.plusSeconds(5).compareTo(lease) >= 0) {
                throw new IllegalArgumentException("Demo car confirm timeout needs a longer worker lease");
            }
            remote.carConfirm = remote.client(timeout);
        }
    }

    private RestClient client(Duration timeout) {
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build());
        factory.setReadTimeout(timeout);
        return RestClient.builder().requestFactory(factory)
                .configureMessageConverters(config -> config.withJsonConverter(new JacksonJsonHttpMessageConverter(json))).build();
    }

    Quote quote(java.util.UUID id, String subject) {
        try {
            return http.get().uri(pricing + "/internal/v1/quotes/{id}?actorUserId={subject}", id, subject)
                    .headers(h -> h.setBearerAuth(token())).retrieve().body(Quote.class);
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 404) {
                throw ReservationStore.fail(HttpStatus.NOT_FOUND, "QUOTE_NOT_FOUND");
            }
            throw ReservationStore.fail(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE");
        } catch (RuntimeException unavailable) {
            throw ReservationStore.fail(HttpStatus.SERVICE_UNAVAILABLE, "PRICING_UNAVAILABLE");
        }
    }

    Quote claim(Case item) {
        return http.post().uri(pricing + "/internal/v1/quotes/{id}/claim", item.quote())
                .headers(h -> h.setBearerAuth(token())).body(new Claim(item.subject(), item.id())).retrieve().body(Quote.class);
    }

    Result validate(Case item, ReservationModel.Line line) {
        try {
            var found = search(item, line);
            var allocation = line.allocation();
            var segment = item.snapshot().itinerary().segments().get(line.segment());
            boolean match = found.stream().anyMatch(offer -> offer.offerId().equals(allocation.offerId())
                    && offer.operator() == allocation.operator() && offer.mode() == allocation.mode()
                    && offer.unitType() == allocation.unitType()
                    && offer.origin().equals(segment.origin()) && offer.destination().equals(segment.destination())
                    && offer.capacity() >= allocation.seatsOrPassengers()
                    && offer.unitPriceMinor() == allocation.unitPrice().amountMinor()
                    && java.util.Objects.equals(offer.priceVersion(), allocation.priceVersion())
                    && (allocation.operator() == Offer.Operator.CAR
                        ? java.util.Objects.equals(offer.startDate(), allocation.startDate())
                            && java.util.Objects.equals(offer.endDate(), allocation.endDate())
                        : offer.departureAt() != null && offer.arrivalAt() != null
                            && offer.departureAt().isEqual(allocation.departureAt()) && offer.arrivalAt().isEqual(allocation.arrivalAt())));
            return match ? Result.success(null, null) : Result.state(State.REJECTED, "OFFER_CHANGED");
        } catch (RuntimeException unavailable) {
            return Result.state(State.UNKNOWN, "OFFER_CHECK_UNAVAILABLE");
        }
    }

    boolean purge(Case item, ReservationModel.Line line) {
        try {
            search(item, line);
            return true;
        } catch (RuntimeException unavailable) {
            return false;
        }
    }

    private List<Offer> search(Case item, ReservationModel.Line line) {
        var allocation = line.allocation();
        var segment = item.snapshot().itinerary().segments().get(line.segment());
        var date = allocation.startDate() != null ? allocation.startDate()
                : allocation.departureAt().atZoneSameInstant(segment.origin().zoneId()).toLocalDate();
        var request = new OfferSearch(segment.origin().cityCode(), segment.destination().cityCode(),
                date, allocation.endDate(), allocation.seatsOrPassengers(), null);
        var offers = http.post().uri(operators.get(allocation.operator()) + "/internal/v1/offers/search")
                .headers(h -> h.setBearerAuth(token())).body(request).retrieve().body(OFFERS);
        if (offers == null) {
            throw new IllegalStateException("Missing search response");
        }
        return offers;
    }

    Result lookup(ReservationModel.Line line, Step step) {
        try {
            var result = http.get().uri(operators.get(line.allocation().operator()) + "/internal/v1/operations/{id}", step.id())
                    .headers(h -> h.setBearerAuth(token())).retrieve().body(OperationStatus.class);
            if (result == null || !step.id().equals(result.operationId())) {
                return Result.state(State.UNKNOWN, "INVALID_PARTICIPANT_REPLY");
            }
            return switch (result.state()) {
                case SUCCEEDED -> switch (step.action()) {
                    case HOLD -> hold(result.hold(), step);
                    case CONFIRM -> confirm(result.confirmation(), step);
                    case CANCEL -> cancelled(result.cancellation(), step);
                    default -> Result.state(State.UNKNOWN, "INVALID_ACTION");
                };
                case REJECTED -> Result.state(State.REJECTED, result.error() == null ? "OPERATOR_REJECTED" : result.error().code());
                case NOT_SENT -> Result.state(State.NOT_SENT, "BRIDGE_NOT_SENT");
                case IN_FLIGHT, PLANNED -> Result.state(State.IN_FLIGHT, "OPERATOR_PENDING");
                case UNKNOWN -> Result.state(State.UNKNOWN, "OPERATOR_OUTCOME_UNKNOWN");
            };
        } catch (RestClientResponseException failure) {
            return failure.getStatusCode().value() == 404 ? Result.state(State.NOT_SENT, "JOURNAL_ABSENT")
                    : Result.state(State.UNKNOWN, "JOURNAL_UNAVAILABLE");
        } catch (RuntimeException unavailable) {
            return Result.state(State.UNKNOWN, "JOURNAL_UNAVAILABLE");
        }
    }

    Result send(Case item, ReservationModel.Line line, Step step) {
        var p = line.allocation();
        try {
            String bearer = token();
            var post = http.post();
            String base = operators.get(p.operator()) + "/internal/v1";
            return switch (step.action()) {
                case HOLD -> {
                    if (barrier != null && !barrier.beforeHold(item, line)) {
                        yield Result.state(State.NOT_SENT, "DEMO_BARRIER_TIMEOUT");
                    }
                    var segment = item.snapshot().itinerary().segments().get(line.segment());
                    var details = p.operator() == Offer.Operator.CAR
                            ? new HoldCommand.CarDetails(p.startDate(), p.endDate(), segment.origin().cityCode(),
                                segment.destination().cityCode(), p.seatsOrPassengers(), p.driverAlias(),
                                p.unitPrice().amountMinor(), p.priceVersion()) : null;
                    var body = new HoldCommand(step.id(), p.offerId(), details == null ? p.seatsOrPassengers() : null,
                            details, item.subject(), line.id());
                    var response = post.uri(base + "/holds").headers(h -> h.setBearerAuth(bearer)).body(body)
                            .retrieve().toEntity(byte[].class);
                    yield response.getStatusCode().value() == 202 ? Result.state(State.IN_FLIGHT, "OPERATOR_PENDING")
                            : hold(json.readValue(response.getBody(), HoldReceipt.class), step);
                }
                case CONFIRM -> {
                    var confirm = p.operator() == Offer.Operator.CAR ? carConfirm.post() : post;
                    var response = confirm.uri(base + "/holds/{reference}/confirm", step.reference()).headers(h -> h.setBearerAuth(bearer))
                            .body(new ConfirmCommand(step.id(), line.id())).retrieve().toEntity(byte[].class);
                    yield response.getStatusCode().value() == 202 ? Result.state(State.IN_FLIGHT, "OPERATOR_PENDING")
                            : confirm(json.readValue(response.getBody(), ConfirmationReceipt.class), step);
                }
                case CANCEL -> {
                    var kind = step.reference().startsWith("BOOKING:") ? CancelCommand.ReferenceKind.BOOKING : CancelCommand.ReferenceKind.HOLD;
                    var reference = step.reference().substring(step.reference().indexOf(':') + 1);
                    var response = post.uri(base + "/cancellations").headers(h -> h.setBearerAuth(bearer))
                            .body(new CancelCommand(step.id(), reference, kind, line.id())).retrieve().toEntity(byte[].class);
                    yield response.getStatusCode().value() == 202 ? Result.state(State.IN_FLIGHT, "OPERATOR_PENDING")
                            : cancelled(json.readValue(response.getBody(), CancellationReceipt.class), step);
                }
                default -> throw new IllegalArgumentException("Invalid operator action");
            };
        } catch (RestClientResponseException failure) {
            if (failure.getStatusCode().value() == 409) {
                try {
                    var error = failure.getResponseBodyAs(OperatorError.class);
                    if (error != null && error.outcome() == OperatorError.Outcome.REJECTED) {
                        return Result.state(State.REJECTED, error.code());
                    }
                } catch (RuntimeException invalidBody) {
                    LOG.debug("Unknown result body for operation {}", step.id());
                }
            }
            return Result.state(State.UNKNOWN, "OPERATOR_OUTCOME_UNKNOWN");
        } catch (RuntimeException unavailable) {
            LOG.debug("Operation {} requires journal lookup: {}", step.id(), unavailable.getClass().getSimpleName());
            return Result.state(State.UNKNOWN, "OPERATOR_OUTCOME_UNKNOWN");
        }
    }

    private static Result hold(HoldReceipt receipt, Step step) {
        return receipt != null && step.id().equals(receipt.operationId()) && receipt.holdId() != null && receipt.expiresAt() != null
                ? Result.success(receipt.holdId(), receipt.expiresAt()) : Result.state(State.UNKNOWN, "INVALID_HOLD_RECEIPT");
    }

    private static Result confirm(ConfirmationReceipt receipt, Step step) {
        return receipt != null && step.id().equals(receipt.operationId()) && receipt.bookingRef() != null
                ? Result.success(receipt.bookingRef(), null) : Result.state(State.UNKNOWN, "INVALID_CONFIRM_RECEIPT");
    }

    private static Result cancelled(CancellationReceipt receipt, Step step) {
        return receipt != null && step.id().equals(receipt.operationId()) && receipt.status() == CancellationReceipt.Status.CANCELLED
                ? Result.success(null, null) : Result.state(State.UNKNOWN, "INVALID_CANCEL_RECEIPT");
    }

    private synchronized String token() {
        if (Instant.now().isBefore(refreshAt) && accessToken != null) {
            return accessToken;
        }
        if (secret.isBlank()) {
            throw new IllegalStateException("Reservation service credentials missing");
        }
        var form = new LinkedMultiValueMap<String,String>();
        form.add("grant_type", "client_credentials");
        var response = http.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(h -> h.setBasicAuth("reservation-service", secret)).body(form).retrieve().body(Token.class);
        if (response == null || response.accessToken() == null || response.expiresIn() < 1) {
            throw new IllegalStateException("No reservation service token");
        }
        this.accessToken = response.accessToken();
        this.refreshAt = Instant.now().plusSeconds(Math.max(1,response.expiresIn()-15));
        return accessToken;
    }

    private record Token(@JsonProperty("access_token") String accessToken, @JsonProperty("expires_in") long expiresIn) {}
}
