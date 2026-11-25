package com.transport.gateway;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.math.BigInteger;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(classes = GatewayApplication.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class GatewayCompatibilityTest {
    private static final String ISSUER = "https://mobility.test/realms/mobility";
    private static final String MINE = "/api/v1/vehicules/mine";
    private static final String SEARCH = "/api/v1/search";
    private static final String TRACE = "00-f66943a35f2a48559ef551af96076aa3-0123456789abcdef-01";
    private static final String TRACE_STATE = "test=mobility";
    private static final String BAGGAGE = "scenario=gateway";
    private static final List<String> IDENTITY_HEADERS = List.of(
            "x-user-id", "X-OWNER-ID", "X-Renter-Id", "x-Actor-User-id");
    private static final Set<String> PROTECTED = Set.of(MINE, "/api/v1/locations",
            "/api/v1/quotes/quote", "/api/v1/admin/regles", "/api/v1/admin/promotions",
            "/api/v1/reservations", "/api/v1/admin/reservations/reservation/operations",
            "/api/v1/notifications");
    private static final KeyPair SIGNER = rsaKey();
    private static final Base64.Encoder BASE64 = Base64.getUrlEncoder().withoutPadding();
    private static final HttpServer TARGET = target();

    @Autowired
    private RouteLocator routeLocator;

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        String endpoint = "http://127.0.0.1:" + TARGET.getAddress().getPort();
        registry.add("mobility.autopartage-url", () -> endpoint);
        registry.add("mobility.bff-url", () -> endpoint);
        registry.add("mobility.pricing-url", () -> endpoint);
        registry.add("mobility.reservation-url", () -> endpoint);
        registry.add("mobility.notification-url", () -> endpoint);
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> ISSUER);
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> endpoint + "/jwks");
    }

    @AfterAll
    static void closeTarget() {
        TARGET.stop(0);
    }

    @Test
    void gatewayStartsWithCompatibilityVerifierEnabled() {
        assertNotNull(routeLocator);
    }

    @Test
    void rejectsInvalidJwtAndInternalPaths() throws Exception {
        assertEquals(401, get(MINE, null).statusCode());
        assertEquals(401, get(MINE, token(ISSUER, "wrong-audience", SIGNER)).statusCode());
        assertEquals(401, get(MINE, token("https://other.test", "mobility-api", SIGNER)).statusCode());
        assertEquals(401, get(MINE, token(ISSUER, "mobility-api", rsaKey())).statusCode());

        String valid = token(ISSUER, "mobility-api", SIGNER);
        assertEquals(403, get("/internal/v1/offers/search", valid).statusCode());
    }

    @Test
    void removesIdentityHeadersAcrossRoutesAndPreservesJwtAndTrace() throws Exception {
        String valid = token(ISSUER, "mobility-api", SIGNER);
        for (String path : PROTECTED) {
            var response = get(path, valid);
            assertEquals(200, response.statusCode(), path);
            assertEquals("Bearer " + valid, response.body(), path);
        }
        assertEquals(200, send(SEARCH, "POST", null).statusCode());
        assertEquals(200, send(SEARCH, "POST", valid).statusCode());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        return send(path, "GET", token);
    }

    private HttpResponse<String> send(String path, String method, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .method(method, HttpRequest.BodyPublishers.noBody())
                .header("traceparent", TRACE).header("tracestate", TRACE_STATE).header("baggage", BAGGAGE);
        IDENTITY_HEADERS.forEach(name -> request.header(name, "spoof"));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static HttpServer target() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            var key = (RSAPublicKey) SIGNER.getPublic();
            var jwks = """
                    {"keys":[{"kty":"RSA","kid":"smoke","alg":"RS256","use":"sig","n":"%s","e":"%s"}]}
                    """.formatted(unsigned(key.getModulus()), unsigned(key.getPublicExponent()));
            server.createContext("/jwks", exchange -> {
                byte[] body = jwks.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.createContext("/api/v1/", exchange -> {
                String path = exchange.getRequestURI().getPath();
                var headers = exchange.getRequestHeaders();
                String authorization = headers.getFirst("Authorization");
                boolean clean = IDENTITY_HEADERS.stream().noneMatch(headers::containsKey)
                        && TRACE.equals(headers.getFirst("traceparent"))
                        && TRACE_STATE.equals(headers.getFirst("tracestate"))
                        && BAGGAGE.equals(headers.getFirst("baggage"));
                boolean expected = SEARCH.equals(path) && authorization == null
                        || PROTECTED.contains(path) && authorization != null;
                byte[] body = (authorization == null ? "" : authorization).getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(clean && expected ? 200 : 400, body.length == 0 ? -1 : body.length);
                try (var response = exchange.getResponseBody()) {
                    response.write(body);
                }
            });
            server.start();
            return server;
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot start test JWKS endpoint", failure);
        }
    }

    private static KeyPair rsaKey() {
        try {
            var generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            return generator.generateKeyPair();
        } catch (java.security.GeneralSecurityException failure) {
            throw new IllegalStateException("Cannot create test signing key", failure);
        }
    }

    private static String token(String issuer, String audience, KeyPair signer) throws Exception {
        String header = """
                {"alg":"RS256","kid":"smoke"}
                """;
        long now = Instant.now().getEpochSecond();
        String claims = """
                {"iss":"%s","sub":"test-subject","aud":["%s"],"iat":%d,"exp":%d}
                """.formatted(issuer, audience, now, now + 120);
        String message = BASE64.encodeToString(header.getBytes(StandardCharsets.UTF_8)) + "."
                + BASE64.encodeToString(claims.getBytes(StandardCharsets.UTF_8));
        var signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(signer.getPrivate());
        signature.update(message.getBytes(StandardCharsets.US_ASCII));
        return message + "." + BASE64.encodeToString(signature.sign());
    }

    private static String unsigned(BigInteger number) {
        byte[] bytes = number.toByteArray();
        return BASE64.encodeToString(bytes[0] == 0 ? Arrays.copyOfRange(bytes, 1, bytes.length) : bytes);
    }
}
