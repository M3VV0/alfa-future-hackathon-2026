package dev.ammmmm.hackathon.smartcreditor.backend.client;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;

@Component
@ConditionalOnProperty(name = "credit.application.worker.enabled", havingValue = "true")
public class ScorerClient {
    public static final String TOKEN_HEADER = "X-Scorer-Token";
    private final RestClient client;

    public ScorerClient(RestClient.Builder builder,
                        @Value("${scorer.base-url:http://localhost:8000}") URI baseUrl,
                        @Value("${scorer.token:}") String token,
                        @Value("${scorer.connect-timeout:PT3S}") Duration connectTimeout,
                        @Value("${scorer.read-timeout:PT10S}") Duration readTimeout) {
        if (token.isBlank()) throw new IllegalArgumentException("SCORER_TOKEN is required when the scoring worker is enabled");
        if (!"http".equals(baseUrl.getScheme()) && !"https".equals(baseUrl.getScheme())) {
            throw new IllegalArgumentException("SCORER_BASE_URL must use http or https");
        }
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(connectTimeout).build());
        factory.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(baseUrl.toString()).requestFactory(factory)
                .defaultHeader(TOKEN_HEADER, token).build();
    }

    public void submit(Map<String, Object> payload) {
        var response = client.post().uri("/request").contentType(MediaType.APPLICATION_JSON)
                .body(payload).retrieve().toBodilessEntity();
        if (response.getStatusCode() != HttpStatus.ACCEPTED) {
            throw new RestClientException("Scorer must acknowledge a persisted request with HTTP 202");
        }
    }
}
