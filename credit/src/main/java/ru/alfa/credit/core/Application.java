package ru.alfa.credit.core;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "application", schema = "core")
public class Application {
  @Id UUID id;

  @Column(name = "company_id", nullable = false)
  UUID companyId;

  @Column(name = "applicant_id", nullable = false)
  UUID applicantId;

  @Column(name = "product_id", nullable = false)
  String productId;

  @Column(name = "product_version", nullable = false)
  int productVersion;

  @Enumerated(EnumType.STRING)
  @Column(nullable = false)
  State status;

  @Column(name = "processing_state", nullable = false)
  String processingState = "IDLE";

  @Column(name = "current_revision", nullable = false)
  int currentRevision;

  @Version long version;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(columnDefinition = "jsonb", nullable = false)
  JsonNode draft;

  @Column(name = "created_at", nullable = false)
  Instant createdAt = Instant.now();

  @Column(name = "updated_at", nullable = false)
  Instant updatedAt = Instant.now();

  protected Application() {}

  public enum State {
    DRAFT,
    SUBMITTED,
    VALIDATING,
    NEEDS_INFO,
    ENRICHING,
    SCORING,
    DECIDING,
    OFFERED,
    ACCEPTED,
    SIGNED_SIMULATED,
    DISBURSED_SIMULATED,
    CANCELLED,
    REJECTED,
    DECLINED,
    EXPIRED
  }
}
