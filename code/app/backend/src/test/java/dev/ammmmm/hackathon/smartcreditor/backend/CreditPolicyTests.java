package dev.ammmmm.hackathon.smartcreditor.backend;

import dev.ammmmm.hackathon.smartcreditor.backend.credit.*;
import dev.ammmmm.hackathon.smartcreditor.backend.model.*;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class CreditPolicyTests {
    private final CreditDecisionPolicy policy = new CreditDecisionPolicy();
    private final UUID id = UUID.randomUUID();
    private CreditApplicationRequest request(CreditApplicationRequest.PaymentFrequency frequency) {
        return new CreditApplicationRequest(UUID.randomUUID(), new BigDecimal("1000000"), 24, frequency,
                CreditApplicationRequest.FinancingPurpose.WORKING_CAPITAL, null);
    }
    private Financials financials(String revenue, String expenses, String debt) {
        return new Financials(new BigDecimal(revenue), new BigDecimal(expenses), new BigDecimal(debt));
    }
    private ScorerResult scoring(String rate) {
        return new ScorerResult(ScorerResult.Status.OK, 24, new BigDecimal(rate), new BigDecimal("1000000"), id);
    }
    @Test
    void decisionsRespectAffordabilityAndExplainReducedAmount() {
        var offer = policy.decide(CreditProduct.find("working-capital"), request(CreditApplicationRequest.PaymentFrequency.MONTHLY),
                financials("100000", "20000", "0"), scoring("20"), Instant.now());
        assertEquals("OFFERED", offer.state());
        assertEquals("TERMS_ADJUSTED", offer.reasonCode());
        assertTrue(offer.amount().compareTo(new BigDecimal("1000000")) < 0);
        assertTrue(offer.payment().compareTo(new BigDecimal("40000")) <= 0);
        var rejected = policy.decide(CreditProduct.find("working-capital"), request(CreditApplicationRequest.PaymentFrequency.MONTHLY),
                financials("100000", "80000", "20000"), scoring("20"), Instant.now());
        assertEquals("REJECTED", rejected.state());
        assertEquals("INSUFFICIENT_INCOME", rejected.reasonCode());
        assertNull(rejected.amount());
    }
    @Test
    void zeroRateAndQuarterlyPaymentsAreCalculatedWithoutDivisionByZero() {
        var offer = policy.decide(CreditProduct.find("working-capital"), request(CreditApplicationRequest.PaymentFrequency.QUARTERLY),
                financials("1000000", "200000", "0"), scoring("0"), Instant.now());
        assertEquals(0, offer.payment().compareTo(new BigDecimal("125000.00")));
        assertEquals(0, offer.amount().compareTo(new BigDecimal("1000000")));
    }
    @Test
    void invalidProviderTermsAreTechnicalErrorsAndNotCreditRejections() {
        assertThrows(ResponseStatusException.class, () -> policy.decide(CreditProduct.find("working-capital"),
                request(CreditApplicationRequest.PaymentFrequency.MONTHLY), financials("1000000", "200000", "0"), scoring("90"), Instant.now()));
    }
    @Test
    void providerDeclineDoesNotExposeRequestedMoneyAsAnOffer() {
        var result = new ScorerResult(ScorerResult.Status.DECLINED, null, null, null, id);
        var decision = policy.decide(CreditProduct.find("working-capital"), request(CreditApplicationRequest.PaymentFrequency.MONTHLY),
                financials("1000000", "200000", "0"), result, Instant.now());
        assertEquals("SCORER_DECLINED", decision.reasonCode());
        assertNull(decision.amount());
    }
}
