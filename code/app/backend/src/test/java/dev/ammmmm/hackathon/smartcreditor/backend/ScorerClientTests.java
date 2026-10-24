package dev.ammmmm.hackathon.smartcreditor.backend;

import com.sun.net.httpserver.HttpServer;
import dev.ammmmm.hackathon.smartcreditor.backend.client.ScorerClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ScorerClientTests {
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> token = new AtomicReference<>();
    private int status = 202;

    @BeforeEach
    void startScorer() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/request", exchange -> {
            token.set(exchange.getRequestHeaders().getFirst(ScorerClient.TOKEN_HEADER));
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stopScorer() {
        server.stop(0);
    }

    private ScorerClient client(String secret) {
        return new ScorerClient(RestClient.builder(), URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                secret, Duration.ofSeconds(1), Duration.ofSeconds(2));
    }

    @Test
    void sendsApplicationAndParametersWithServiceTokenAndWaitsOnlyForAck() throws Exception {
        UUID id = UUID.randomUUID();
        client("test-token").submit(Map.of("id", id.toString(), "parameters", Map.of(
                "requested_amount", "999999999999999.99", "term_months", 24)));
        var json = JsonMapper.builder().build().readTree(body.get());
        assertEquals(id.toString(), json.get("id").asText());
        assertEquals("999999999999999.99", json.get("parameters").get("requested_amount").asText());
        assertEquals(24, json.get("parameters").get("term_months").asInt());
        assertEquals("test-token", token.get());
    }

    @Test
    void propagatesScorerHttpFailures() {
        status = 503;
        var error = assertThrows(RestClientResponseException.class,
                () -> client("test-token").submit(Map.of("id", UUID.randomUUID().toString())));
        assertEquals(503, error.getStatusCode().value());
    }

    @Test
    void doesNotTreatUnpersistedOrRedirectedResponseAsAcceptance() {
        status = 200;
        assertThrows(RestClientException.class,
                () -> client("test-token").submit(Map.of("id", UUID.randomUUID().toString())));
        status = 302;
        assertThrows(RestClientException.class,
                () -> client("test-token").submit(Map.of("id", UUID.randomUUID().toString())));
    }

    @Test
    void refusesEnabledClientWithoutServiceToken() {
        assertThrows(IllegalArgumentException.class, () -> client(" "));
    }
}
