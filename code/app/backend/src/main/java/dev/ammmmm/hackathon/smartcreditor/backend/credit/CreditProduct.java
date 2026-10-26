package dev.ammmmm.hackathon.smartcreditor.backend.credit;

import dev.ammmmm.hackathon.smartcreditor.backend.model.CreditApplicationRequest.FinancingPurpose;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.util.List;

public record CreditProduct(String id, String name, BigDecimal minAmount, BigDecimal maxAmount,
                            int minMonths, int maxMonths, int minBusinessAgeMonths, FinancingPurpose purpose,
                            String version) {
    public static final List<CreditProduct> CATALOG = List.of(
            new CreditProduct("working-capital", "Оборотные средства", new BigDecimal("100000"),
                    new BigDecimal("5000000"), 6, 36, 6, FinancingPurpose.WORKING_CAPITAL, "products-v1"),
            new CreditProduct("equipment", "Оборудование", new BigDecimal("500000"),
                    new BigDecimal("10000000"), 12, 60, 12, FinancingPurpose.EQUIPMENT, "products-v1"));

    public static CreditProduct find(String id) {
        return CATALOG.stream().filter(product -> product.id.equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "UNKNOWN_PRODUCT"));
    }
}
