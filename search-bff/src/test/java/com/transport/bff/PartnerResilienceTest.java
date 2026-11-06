package com.transport.bff;

import com.sun.net.httpserver.HttpServer;
import com.transport.mobility.contracts.v1.Offer;
import com.transport.mobility.contracts.v1.OfferSearch;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClientResponseException;

import static org.junit.jupiter.api.Assertions.*;

class PartnerResilienceTest {
    @Test
    void stopsSendingFailedAirReadsAndKeepsRailAvailable() throws Exception {
        var calls = new AtomicInteger();
        var air = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var rail = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        air.createContext("/internal/v1/offers/search", exchange -> {
            calls.incrementAndGet();
            try (exchange) {
                exchange.sendResponseHeaders(503, -1);
            }
        });
        rail.createContext("/internal/v1/offers/search", exchange -> {
            try (exchange) {
                byte[] empty = "[]".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, empty.length);
                exchange.getResponseBody().write(empty);
            }
        });
        air.start();
        rail.start();
        try {
            var client = new PartnerClient(address(rail), address(air), "", "", "", "");
            var query = new OfferSearch("CAS", "RAB", LocalDate.of(2026, 11, 15), null, 1, null);
            for (int i = 0; i < 4; i++) {
                assertThrows(RestClientResponseException.class, () -> client.search(Offer.Operator.AIR, query, "test"));
            }
            assertThrows(CallNotPermittedException.class, () -> client.search(Offer.Operator.AIR, query, "test"));
            assertEquals(4, calls.get());
            assertTrue(client.search(Offer.Operator.RAIL, query, "test").isEmpty());
        } finally {
            rail.stop(0);
            air.stop(0);
        }
    }

    private static String address(HttpServer server) {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }
}
