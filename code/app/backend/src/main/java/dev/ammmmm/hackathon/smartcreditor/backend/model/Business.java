package dev.ammmmm.hackathon.smartcreditor.backend.model;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.jspecify.annotations.Nullable;

@Entity
@Table(name = "business")
public class Business {
    @Id
    private UUID id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Type type;

    @Column(name = "tax_id", nullable = false, unique = true, length = 12)
    private String taxId;

    @Column(length = 15)
    private @Nullable String ogrn;

    @Column(name = "name", columnDefinition = "text")
    private @Nullable String name;

    @Column(name = "le_kpp", length = 9)
    private @Nullable String leKpp;

    @Column(name = "address", columnDefinition = "text")
    private @Nullable String address;

    @Column(name = "le_formation_method_name", columnDefinition = "text")
    private @Nullable String leFormationMethodName;

    @Column(name = "le_participants_count")
    private @Nullable Integer leParticipantsCount;

    @Column(name = "le_has_predecessors")
    private @Nullable Boolean leHasPredecessors;

    @Column(name = "le_has_successors")
    private @Nullable Boolean leHasSuccessors;

    @Enumerated(EnumType.STRING)
    @Column(name = "ie_sex", length = 16)
    private @Nullable Sex ieSex;

    @Enumerated(EnumType.STRING)
    @Column(name = "ie_citizenship_kind", length = 16)
    private @Nullable CitizenshipKind ieCitizenshipKind;

    @Column(name = "ie_country_code_raw", columnDefinition = "text")
    private @Nullable String ieCountryCodeRaw;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable Location location;

    @Column(name = "msp_included_on")
    private @Nullable LocalDate mspIncludedOn;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable List<Product> products;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private @Nullable List<Partnership> partnerships;

    @Column(name = "registry_fetched_at")
    private @Nullable Instant registryFetchedAt;

    @Column(name = "msp_fetched_at")
    private @Nullable Instant mspFetchedAt;

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

    public @Nullable String getName() {
        return name;
    }

    public void setName(@Nullable String name) {
        this.name = name;
    }

    public @Nullable String getLeKpp() {
        return leKpp;
    }

    public void setLeKpp(@Nullable String leKpp) {
        this.leKpp = leKpp;
    }

    public @Nullable String getAddress() {
        return address;
    }

    public void setAddress(@Nullable String address) {
        this.address = address;
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

    public @Nullable Instant getRegistryFetchedAt() {
        return registryFetchedAt;
    }

    public void setRegistryFetchedAt(@Nullable Instant registryFetchedAt) {
        this.registryFetchedAt = registryFetchedAt;
    }

    public @Nullable Instant getMspFetchedAt() {
        return mspFetchedAt;
    }

    public void setMspFetchedAt(@Nullable Instant mspFetchedAt) {
        this.mspFetchedAt = mspFetchedAt;
    }

    public enum Type {
        INDIVIDUAL_ENTREPRENEUR,
        LEGAL_ENTITY
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
            String okpd2Code,
            boolean innovativeOrHightech) {
    }

    public record Partnership(
            String customerName,
            String agreementNumber,
            LocalDate agreementDate) {
    }
}
