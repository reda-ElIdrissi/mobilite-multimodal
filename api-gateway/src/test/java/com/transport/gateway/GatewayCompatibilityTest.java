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
    void authenticatesJwtAndRemovesIdentityHeadersBeforeForwarding() throws Exception {
        assertEquals(401, get(MINE, null).statusCode());
        assertEquals(401, get(MINE, token(ISSUER, "wrong-audience", SIGNER)).statusCode());
        assertEquals(401, get(MINE, token("https://other.test", "mobility-api", SIGNER)).statusCode());
        assertEquals(401, get(MINE, token(ISSUER, "mobility-api", rsaKey())).statusCode());

        String valid = token(ISSUER, "mobility-api", SIGNER);
        assertEquals(200, get(MINE, valid).statusCode());
        assertEquals(403, get("/internal/v1/offers/search", valid).statusCode());
    }

    private HttpResponse<String> get(String path, String token) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("X-User-Id", "spoof").header("X-Owner-Id", "spoof");
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
            server.createContext(MINE, exchange -> {
                var headers = exchange.getRequestHeaders();
                boolean clean = !headers.containsKey("X-User-Id") && !headers.containsKey("X-Owner-Id")
                        && headers.containsKey("Authorization");
                exchange.sendResponseHeaders(clean ? 200 : 400, -1);
                exchange.close();
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
