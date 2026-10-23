package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Entity
public class Business {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(nullable = false, unique = true, length = 12)
    private String taxId;

    @Column(nullable = false, insertable = false, updatable = false)
    private Instant creationTime;

    @Column(length = 15)
    private @Nullable String ogrn;

    @Column(columnDefinition = "text")
    private @Nullable String registeredName;

    @Column(columnDefinition = "text")
    private @Nullable String registeredAddress;

    private @Nullable LocalDate registeredDate;

    @Enumerated(EnumType.STRING)
    @Column(length = 32)
    private @Nullable RegistrationStatus registrationStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable List<LegalEvent> legalEvents;

    @Column(length = 8)
    private @Nullable String primaryOkvedCode;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable List<String> additionalOkvedCodes;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable List<Product> products;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable Location location;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable List<Partnership> partnerships;

    private @Nullable LocalDate mspIncludedOn;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private @Nullable MspCategory mspCategory;

    private @Nullable Integer mspAverageHeadcount;

    private @Nullable Integer mspReportYear;

    private @Nullable Boolean mspNewlyRegistered;

    private @Nullable Boolean mspSocialEnterprise;

    private @Nullable LocalDate mspSnapshotOn;

    // Fields specific for Business.type==Type.INDIVIDUAL_ENTREPRENEUR
    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private @Nullable Sex ieSex;

    @Enumerated(EnumType.STRING)
    @Column(length = 16)
    private @Nullable CitizenshipKind ieCitizenshipKind;

    @Column(columnDefinition = "text")
    private @Nullable String ieCountryCodeRaw;

    // Fields specific for Business.type==Type.LEGAL_ENTITY
    @Column(length = 9)
    private @Nullable String leKpp;

    @Column(columnDefinition = "text")
    private @Nullable String leFormationMethodName;

    private @Nullable Integer leParticipantsCount;

    private @Nullable Boolean leHasPredecessors;

    private @Nullable Boolean leHasSuccessors;

    protected Business() {
    }

    public Business(Type type, String taxId) {
        this.id = UUID.randomUUID();
        this.type = Objects.requireNonNull(type);
        this.taxId = Objects.requireNonNull(taxId);
    }

    public UUID getId() {
        return id;
    }

    public Type getType() {
        return type;
    }

    public String getTaxId() {
        return taxId;
    }

    public @Nullable String getOgrn() {
        return ogrn;
    }

    public void setOgrn(@Nullable String ogrn) {
        this.ogrn = ogrn;
    }

    public @Nullable String getRegisteredName() {
        return registeredName;
    }

    public void setRegisteredName(@Nullable String registeredName) {
        this.registeredName = registeredName;
    }

    public @Nullable String getLeKpp() {
        return leKpp;
    }

    public void setLeKpp(@Nullable String leKpp) {
        this.leKpp = leKpp;
    }

    public @Nullable String getRegisteredAddress() {
        return registeredAddress;
    }

    public void setRegisteredAddress(@Nullable String registeredAddress) {
        this.registeredAddress = registeredAddress;
    }

    public @Nullable LocalDate getRegisteredDate() {
        return registeredDate;
    }

    public void setRegisteredDate(@Nullable LocalDate registeredDate) {
        this.registeredDate = registeredDate;
    }

    public @Nullable RegistrationStatus getRegistrationStatus() {
        return registrationStatus;
    }

    public void setRegistrationStatus(@Nullable RegistrationStatus registrationStatus) {
        this.registrationStatus = registrationStatus;
    }

    public @Nullable List<LegalEvent> getLegalEvents() {
        return legalEvents == null ? null : List.copyOf(legalEvents);
    }

    public void setLegalEvents(@Nullable List<LegalEvent> legalEvents) {
        this.legalEvents = legalEvents == null ? null : List.copyOf(legalEvents);
    }

    public @Nullable String getLeFormationMethodName() {
        return leFormationMethodName;
    }

    public void setLeFormationMethodName(@Nullable String leFormationMethodName) {
        this.leFormationMethodName = leFormationMethodName;
    }

    public @Nullable Integer getLeParticipantsCount() {
        return leParticipantsCount;
    }

    public void setLeParticipantsCount(@Nullable Integer leParticipantsCount) {
        this.leParticipantsCount = leParticipantsCount;
    }

    public @Nullable Boolean getLeHasPredecessors() {
        return leHasPredecessors;
    }

    public void setLeHasPredecessors(@Nullable Boolean leHasPredecessors) {
        this.leHasPredecessors = leHasPredecessors;
    }

    public @Nullable Boolean getLeHasSuccessors() {
        return leHasSuccessors;
    }

    public void setLeHasSuccessors(@Nullable Boolean leHasSuccessors) {
        this.leHasSuccessors = leHasSuccessors;
    }

    public @Nullable Sex getIeSex() {
        return ieSex;
    }

    public void setIeSex(@Nullable Sex ieSex) {
        this.ieSex = ieSex;
    }

    public @Nullable CitizenshipKind getIeCitizenshipKind() {
        return ieCitizenshipKind;
    }

    public void setIeCitizenshipKind(@Nullable CitizenshipKind ieCitizenshipKind) {
        this.ieCitizenshipKind = ieCitizenshipKind;
    }

    public @Nullable String getIeCountryCodeRaw() {
        return ieCountryCodeRaw;
    }

    public void setIeCountryCodeRaw(@Nullable String ieCountryCodeRaw) {
        this.ieCountryCodeRaw = ieCountryCodeRaw;
    }

    public @Nullable Location getLocation() {
        return location;
    }

    public void setLocation(@Nullable Location location) {
        this.location = location;
    }

    public @Nullable LocalDate getMspIncludedOn() {
        return mspIncludedOn;
    }

    public void setMspIncludedOn(@Nullable LocalDate mspIncludedOn) {
        this.mspIncludedOn = mspIncludedOn;
    }

    public @Nullable String getPrimaryOkvedCode() {
        return primaryOkvedCode;
    }

    public void setPrimaryOkvedCode(@Nullable String primaryOkvedCode) {
        this.primaryOkvedCode = primaryOkvedCode;
    }

    public @Nullable List<String> getAdditionalOkvedCodes() {
        return additionalOkvedCodes == null ? null : List.copyOf(additionalOkvedCodes);
    }

    public void setAdditionalOkvedCodes(@Nullable List<String> additionalOkvedCodes) {
        this.additionalOkvedCodes =
                additionalOkvedCodes == null ? null : List.copyOf(additionalOkvedCodes);
    }

    public @Nullable List<Product> getProducts() {
        return products == null ? null : List.copyOf(products);
    }

    public void setProducts(@Nullable List<Product> products) {
        this.products = products == null ? null : List.copyOf(products);
    }

    public @Nullable List<Partnership> getPartnerships() {
        return partnerships == null ? null : List.copyOf(partnerships);
    }

    public void setPartnerships(@Nullable List<Partnership> partnerships) {
        this.partnerships = partnerships == null ? null : List.copyOf(partnerships);
    }

    public @Nullable MspCategory getMspCategory() {
        return mspCategory;
    }

    public void setMspCategory(@Nullable MspCategory mspCategory) {
        this.mspCategory = mspCategory;
    }

    public @Nullable Integer getMspAverageHeadcount() {
        return mspAverageHeadcount;
    }

    public void setMspAverageHeadcount(@Nullable Integer mspAverageHeadcount) {
        this.mspAverageHeadcount = mspAverageHeadcount;
    }

    public @Nullable Integer getMspReportYear() {
        return mspReportYear;
    }

    public void setMspReportYear(@Nullable Integer mspReportYear) {
        this.mspReportYear = mspReportYear;
    }

    public @Nullable Boolean getMspNewlyRegistered() {
        return mspNewlyRegistered;
    }

    public void setMspNewlyRegistered(@Nullable Boolean mspNewlyRegistered) {
        this.mspNewlyRegistered = mspNewlyRegistered;
    }

    public @Nullable Boolean getMspSocialEnterprise() {
        return mspSocialEnterprise;
    }

    public void setMspSocialEnterprise(@Nullable Boolean mspSocialEnterprise) {
        this.mspSocialEnterprise = mspSocialEnterprise;
    }

    public @Nullable LocalDate getMspSnapshotOn() {
        return mspSnapshotOn;
    }

    public void setMspSnapshotOn(@Nullable LocalDate mspSnapshotOn) {
        this.mspSnapshotOn = mspSnapshotOn;
    }

    public enum Type {
        INDIVIDUAL_ENTREPRENEUR,
        LEGAL_ENTITY
    }

    public enum RegistrationStatus {
        ACTIVE,
        REORGANIZING,
        TERMINATING,
        TERMINATED,
        OTHER
    }

    public enum MspCategory {
        MICRO,
        SMALL,
        MEDIUM
    }

    public enum LegalEventKind {
        REORGANIZATION_COMPLETED
    }

    public enum Sex {
        MALE, FEMALE
    }

    public enum CitizenshipKind {
        RUSSIAN, FOREIGN, STATELESS
    }

    public record Location(
            @Nullable String regionCode,
            @Nullable String region,
            @Nullable String district,
            @Nullable String city,
            @Nullable String settlement) {
    }

    public record Product(
            String okpdCode,
            boolean innovativeOrHightech) {
    }

    public record LegalEvent(
            LegalEventKind kind,
            @Nullable String sourceStatusCode,
            String description,
            LocalDate eventDate,
            LocalDate recordDate) {
    }

    public record Partnership(
            String customerName,
            String agreementNumber,
            LocalDate agreementDate) {
    }
}
