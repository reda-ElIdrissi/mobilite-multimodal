package com.transport.bff;

import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.OfferSearch;
import com.transport.mobility.contracts.v1.QuoteContracts.Issue;
import com.transport.mobility.contracts.v1.QuoteContracts.Quote;
import com.transport.mobility.contracts.v1.QuoteContracts.Price;
import com.transport.mobility.contracts.v1.QuoteContracts.PreviewBatch;
import java.util.UUID;
import java.util.EnumMap;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.springframework.http.HttpStatusCode;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.server.ResponseStatusException;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import tools.jackson.databind.cfg.DateTimeFeature;
import tools.jackson.databind.json.JsonMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
class PartnerClient {
    private static final ParameterizedTypeReference<List<Offer>> OFFERS = new ParameterizedTypeReference<>() {};
    private final Map<Offer.Operator, String> endpoints;
    private final Map<Offer.Operator, CircuitBreaker> breakers = new EnumMap<>(Offer.Operator.class);
    private final Map<Offer.Operator, Bulkhead> bulkheads = new EnumMap<>(Offer.Operator.class);
    private final String pricingUrl;
    private final String tokenUrl;
    private final String secret;
    private final RestClient partners = client(Duration.ofSeconds(5));
    private final RestClient identity = client(Duration.ofSeconds(2));
    private String token;
    private Instant refreshAt = Instant.EPOCH;

    PartnerClient(@Value("${mobility.rail-url}") String rail,
            @Value("${mobility.air-url}") String air, @Value("${mobility.car-url}") String car,
            @Value("${mobility.pricing-url}") String pricingUrl,
            @Value("${mobility.token-url}") String tokenUrl, @Value("${mobility.bff-secret}") String secret) {
        this.endpoints = Map.of(Offer.Operator.RAIL, rail, Offer.Operator.AIR, air, Offer.Operator.CAR, car);
        this.pricingUrl = pricingUrl;
        this.tokenUrl = tokenUrl;
        this.secret = secret;
        var config = CircuitBreakerConfig.custom().slidingWindowSize(8).minimumNumberOfCalls(4)
                .failureRateThreshold(50).waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(2)
                .ignoreException(error -> error instanceof RestClientResponseException response
                        && response.getStatusCode().is4xxClientError()).build();
        for (var operator : Offer.Operator.values()) {
            breakers.put(operator, CircuitBreaker.of(operator.name(), config));
        }
        configureBulkheads(9);
    }

    @Value("${mobility.search.partner-concurrency:9}")
    void configureBulkheads(int concurrency) {
        var config = BulkheadConfig.custom().maxConcurrentCalls(concurrency).maxWaitDuration(Duration.ZERO).build();
        for (var operator : Offer.Operator.values()) {
            bulkheads.put(operator, Bulkhead.of(operator.name(), config));
        }
    }

    List<Offer> search(Offer.Operator operator, OfferSearch request, String accessToken) {
        return bulkheads.get(operator).executeSupplier(() ->
                breakers.get(operator).executeSupplier(() -> searchPartner(operator, request, accessToken)));
    }

    private List<Offer> searchPartner(Offer.Operator operator, OfferSearch request, String accessToken) {
        var offers = partners.post().uri(endpoints.get(operator) + "/internal/v1/offers/search")
                .headers(headers -> headers.setBearerAuth(accessToken)).body(request).retrieve().body(OFFERS);
        if (offers == null || offers.stream().anyMatch(offer -> offer == null || offer.operator() != operator)) {
            throw new IllegalStateException("Invalid operator response");
        }
        return offers;
    }

    List<Price> preview(PreviewBatch batch, String accessToken) {
        return partners.post().uri(pricingUrl + "/internal/v1/pricing/preview")
                .headers(headers -> headers.setBearerAuth(accessToken)).body(batch).retrieve()
                .body(new ParameterizedTypeReference<List<Price>>() {});
    }

    Quote issue(Issue request, String accessToken) {
        return pricing(() -> partners.post().uri(pricingUrl + "/internal/v1/quotes")
                .headers(headers -> headers.setBearerAuth(accessToken)).body(request).retrieve().body(Quote.class));
    }

    Quote getQuote(UUID id, String subject, String accessToken) {
        return pricing(() -> partners.get().uri(pricingUrl + "/internal/v1/quotes/{id}?actorUserId={subject}", id, subject)
                .headers(headers -> headers.setBearerAuth(accessToken)).retrieve().body(Quote.class));
    }

    private static Quote pricing(java.util.function.Supplier<Quote> call) {
        try {
            Quote result = call.get();
            if (result == null) {
                throw new ResponseStatusException(HttpStatusCode.valueOf(503), "PRICING_UNAVAILABLE");
            }
            return result;
        } catch (RestClientResponseException response) {
            int code = response.getStatusCode().value();
            if (code == 400 || code == 404 || code == 409 || code == 410) {
                var body = response.getResponseBodyAs(org.springframework.http.ProblemDetail.class);
                throw new ResponseStatusException(response.getStatusCode(), body == null ? "QUOTE_REJECTED" : body.getDetail());
            }
            throw new ResponseStatusException(HttpStatusCode.valueOf(503), "PRICING_UNAVAILABLE");
        } catch (RestClientException unavailable) {
            throw new ResponseStatusException(HttpStatusCode.valueOf(503), "PRICING_UNAVAILABLE");
        }
    }

    synchronized String accessToken() {
        var now = Instant.now();
        if (now.isBefore(refreshAt)) {
            if (token == null) {
                throw new IllegalStateException("Search service token temporarily unavailable");
            }
            return token;
        }
        if (secret.isBlank()) {
            throw new IllegalStateException("Search service credential missing");
        }
        var form = new LinkedMultiValueMap<String, String>();
        form.add("grant_type", "client_credentials");
        TokenResponse response;
        try {
            response = identity.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> headers.setBasicAuth("search-bff", secret))
                .body(form).retrieve().body(TokenResponse.class);
            if (response == null || response.accessToken() == null || response.expiresIn() <= 0) {
                throw new IllegalStateException("No search service token issued");
            }
        } catch (RuntimeException unavailable) {
            token = null;
            refreshAt = Instant.now().plusSeconds(2);
            throw unavailable;
        }
        token = response.accessToken();
        refreshAt = now.plusSeconds(Math.max(1, response.expiresIn() - 10));
        return token;
    }

    private static RestClient client(Duration timeout) {
        var http = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build();
        var factory = new JdkClientHttpRequestFactory(http);
        factory.setReadTimeout(timeout);
        var json = JsonMapper.builder().disable(DateTimeFeature.ADJUST_DATES_TO_CONTEXT_TIME_ZONE).build();
        return RestClient.builder().requestFactory(factory)
                .configureMessageConverters(converters -> converters.withJsonConverter(new JacksonJsonHttpMessageConverter(json)))
                .build();
    }

    private record TokenResponse(@JsonProperty("access_token") String accessToken,
                                 @JsonProperty("expires_in") long expiresIn) {}
}
