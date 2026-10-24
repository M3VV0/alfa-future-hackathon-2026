package dev.ammmmm.hackathon.smartcreditor.backend.client;

import dev.ammmmm.hackathon.smartcreditor.backend.model.Business;
import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplication;
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
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Locale;
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

    public static Map<String, Object> createPayload(CreditApplication application, Business business) {
        var request = application.getRequest();
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("requested_amount", request.requestedAmount().toPlainString());
        parameters.put("currency", "RUB");
        parameters.put("term_months", request.termMonths());
        parameters.put("payment_frequency", category(request.paymentFrequency()));
        parameters.put("financing_purpose", category(request.financingPurpose()));
        parameters.put("declared_collateral_value", request.declaredCollateralValue() == null
                ? null : request.declaredCollateralValue().toPlainString());
        parameters.put("applicant_inn", business.getTaxId());
        String entityType = business.getType() == Business.Type.LEGAL_ENTITY ? "legal_entity" : "ip";
        parameters.put("applicant_entity_type", entityType);
        parameters.put("entity_type", entityType);
        parameters.put("msp_category", category(business.getMspCategory()));
        parameters.put("snapshot_on", application.getCreationTime().atZone(ZoneOffset.UTC).toLocalDate().toString());
        parameters.put("average_headcount", business.getMspAverageHeadcount());
        parameters.put("report_year", business.getMspReportYear());
        parameters.put("msp_included_on", date(business.getMspIncludedOn()));
        parameters.put("is_newly_registered", business.getMspNewlyRegistered());
        parameters.put("is_social_enterprise", business.getMspSocialEnterprise());
        parameters.put("legal_name", business.getRegisteredName());
        parameters.put("kpp", business.getLeKpp());
        parameters.put("ogrn", business.getType() == Business.Type.LEGAL_ENTITY ? business.getOgrn() : null);
        parameters.put("ogrnip", business.getType() == Business.Type.INDIVIDUAL_ENTREPRENEUR ? business.getOgrn() : null);
        parameters.put("sex", category(business.getIeSex()));
        parameters.put("citizenship_kind", category(business.getIeCitizenshipKind()));
        parameters.put("country_code_raw", business.getIeCountryCodeRaw());
        parameters.put("formation_method_name", business.getLeFormationMethodName());
        parameters.put("participants_count", business.getLeParticipantsCount());
        parameters.put("has_predecessors", business.getLeHasPredecessors());
        parameters.put("has_successors", business.getLeHasSuccessors());
        var location = business.getLocation();
        parameters.put("location", location == null ? null : fields(
                "region_code", location.regionCode(), "region", location.region(), "district", location.district(),
                "city", location.city(), "settlement", location.settlement()));
        var events = business.getLegalEvents();
        parameters.put("legal_events", events == null ? null : events.stream().map(event -> fields(
                "kind", category(event.kind()), "source_status_code", event.sourceStatusCode(),
                "description", event.description(), "event_date", date(event.eventDate()),
                "record_date", date(event.recordDate()))).toList());
        var products = business.getProducts();
        parameters.put("products", products == null ? null : products.stream().map(product -> fields(
                "okpd2_code", product.okpdCode(), "innovative_or_hightech", product.innovativeOrHightech())).toList());
        var partnerships = business.getPartnerships();
        parameters.put("partnerships", partnerships == null ? null : partnerships.stream().map(partnership -> fields(
                "customer_name", partnership.customerName(), "agreement_number", partnership.agreementNumber(),
                "agreement_date", date(partnership.agreementDate()))).toList());
        return fields("id", application.getId().toString(), "parameters", parameters);
    }

    public void submit(Map<String, Object> payload) {
        var response = client.post().uri("/request").contentType(MediaType.APPLICATION_JSON)
                .body(payload).retrieve().toBodilessEntity();
        if (response.getStatusCode() != HttpStatus.ACCEPTED) {
            throw new RestClientException("Scorer must acknowledge a persisted request with HTTP 202");
        }
    }

    private static String category(Enum<?> value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    private static String date(java.time.LocalDate value) {
        return value == null ? null : value.toString();
    }

    private static Map<String, Object> fields(Object... pairs) {
        Map<String, Object> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) values.put((String) pairs[i], pairs[i + 1]);
        return values;
    }
}
