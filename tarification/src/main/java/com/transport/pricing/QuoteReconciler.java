package com.transport.pricing;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.transport.mobility.events.ReservationEvent.Outcome;
import java.net.http.HttpClient;
import java.time.Duration;
import java.time.Instant;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;

@Component
class QuoteReconciler {
    private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(QuoteReconciler.class);
    private final QuoteResults results;
    private final RestClient http;
    private final String reservationUrl;
    private final String tokenUrl;
    private final String secret;
    private String accessToken;
    private Instant refreshAt = Instant.EPOCH;

    QuoteReconciler(QuoteResults results, @Value("${mobility.pricing.reservation-url}") String reservationUrl,
            @Value("${mobility.pricing.token-url}") String tokenUrl, @Value("${mobility.pricing.secret}") String secret) {
        this.results = results;
        this.reservationUrl = reservationUrl;
        this.tokenUrl = tokenUrl;
        this.secret = secret;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofMillis(1500)).build());
        factory.setReadTimeout(Duration.ofSeconds(4));
        this.http = RestClient.builder().requestFactory(factory).build();
    }

    @Scheduled(fixedDelayString="${mobility.pricing.reconcile-interval-ms:10000}")
    void reconcile() {
        if (secret.isBlank()) {
            return;
        }
        var due = results.due();
        if (due.isEmpty()) {
            return;
        }
        String bearer;
        try {
            bearer = token();
        } catch (RuntimeException unavailable) {
            LOG.warn("Quote reconciliation token unavailable: {}", unavailable.getClass().getSimpleName());
            return;
        }
        for (var quote : due) {
            try {
                var outcome = http.get().uri(reservationUrl + "/internal/v1/reservations/{id}/outcome", quote.reservation())
                        .headers(headers -> headers.setBearerAuth(bearer)).retrieve().body(Outcome.class);
                if (outcome != null) {
                    results.reconcile(outcome, quote);
                }
            } catch (RuntimeException unavailable) {
                LOG.warn("Quote reconciliation deferred for quote {}: {}", quote.quote(), unavailable.getClass().getSimpleName());
            }
        }
    }

    private String token() {
        if (accessToken != null && Instant.now().isBefore(refreshAt)) {
            return accessToken;
        }
        var form = new LinkedMultiValueMap<String,String>();
        form.add("grant_type","client_credentials");
        var response = http.post().uri(tokenUrl).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .headers(headers -> headers.setBasicAuth("pricing-service",secret))
                .body(form).retrieve().body(Token.class);
        if (response == null || response.value() == null || response.expiresIn() < 1) {
            throw new IllegalStateException("Missing pricing service token");
        }
        accessToken = response.value();
        refreshAt = Instant.now().plusSeconds(Math.max(1,response.expiresIn()-15));
        return accessToken;
    }

    private record Token(@JsonProperty("access_token") String value, @JsonProperty("expires_in") long expiresIn) {}
}
