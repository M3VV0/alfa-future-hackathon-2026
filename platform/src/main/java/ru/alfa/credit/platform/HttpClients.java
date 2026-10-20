package ru.alfa.credit.platform;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.context.annotation.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class HttpClients {
  @Bean
  RestClient.Builder restClientBuilder() {
    var factory =
        new JdkClientHttpRequestFactory(
            HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build());
    factory.setReadTimeout(Duration.ofSeconds(5));
    return RestClient.builder()
        .requestFactory(factory)
        .requestInterceptor(
            (request, body, execution) -> {
              request.getHeaders().set("X-Trace-Id", TraceFilter.current());
              return execution.execute(request, body);
            });
  }
}
