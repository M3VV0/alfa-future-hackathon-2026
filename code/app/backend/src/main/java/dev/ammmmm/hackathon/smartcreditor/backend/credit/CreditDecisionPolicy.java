package dev.ammmmm.hackathon.smartcreditor.backend.credit;

import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationRequest;
import dev.ammmmm.hackathon.smartcreditor.backend.model.ScorerResult;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/** Product and affordability policy, independent of the future Python model. */
@Component
public class CreditDecisionPolicy {
    public static final String VERSION = "decision-v1";
    public record Decision(String state, String reasonCode, BigDecimal amount, Integer months,
                           BigDecimal annualRate, BigDecimal payment, String paymentFrequency,
                           Instant expiresAt, String policyVersion, String productVersion) {}

    public Decision decide(CreditProduct product, CreditApplicationRequest request, Financials financials,
                           ScorerResult scoring, Instant now) {
        if (scoring.status() == ScorerResult.Status.DECLINED) return rejected(product, "SCORER_DECLINED");
        int step = request.paymentFrequency() == CreditApplicationRequest.PaymentFrequency.QUARTERLY ? 3 : 1;
        if (!scoring.isTermsValid() || scoring.months() < product.minMonths()
                || scoring.months() > product.maxMonths() || scoring.months() % step != 0
                || scoring.percent().compareTo(new BigDecimal("60")) > 0) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT, "INVALID_SCORER_TERMS");
        }
        BigDecimal available = financials.monthlyRevenue().subtract(financials.monthlyExpenses())
                .multiply(new BigDecimal("0.5")).subtract(financials.existingMonthlyPayment())
                .multiply(BigDecimal.valueOf(step));
        if (available.signum() <= 0) return rejected(product, "INSUFFICIENT_INCOME");
        int periods = scoring.months() / step;
        double factor = paymentFactor(scoring.percent(), periods, step);
        BigDecimal affordable = available.divide(BigDecimal.valueOf(factor), 0, RoundingMode.DOWN);
        BigDecimal amount = request.requestedAmount().min(scoring.sum()).min(product.maxAmount());
        if (affordable.compareTo(amount) < 0) {
            amount = affordable.divide(new BigDecimal("1000"), 0, RoundingMode.DOWN).multiply(new BigDecimal("1000"));
        }
        if (amount.compareTo(product.minAmount()) < 0) return rejected(product, "INSUFFICIENT_INCOME");
        boolean changed = amount.compareTo(request.requestedAmount()) != 0 || !scoring.months().equals(request.termMonths());
        String reason = changed ? "TERMS_ADJUSTED" : scoring.status() == ScorerResult.Status.OFFER ? "SCORER_OFFER" : "APPROVED_CONDITIONS";
        return new Decision("OFFERED", reason, amount, scoring.months(), scoring.percent(),
                amount.multiply(BigDecimal.valueOf(factor)).setScale(2, RoundingMode.HALF_UP),
                request.paymentFrequency().name(), now.plus(7, ChronoUnit.DAYS), VERSION, product.version());
    }

    public Decision rejected(CreditProduct product, String reason) {
        return new Decision("REJECTED", reason, null, null, null, null, null, null, VERSION, product.version());
    }

    private static double paymentFactor(BigDecimal rate, int periods, int step) {
        double periodicRate = rate.doubleValue() / 100 / 12 * step;
        return periodicRate == 0 ? 1.0 / periods : periodicRate / -Math.expm1(-periods * Math.log1p(periodicRate));
    }
}
