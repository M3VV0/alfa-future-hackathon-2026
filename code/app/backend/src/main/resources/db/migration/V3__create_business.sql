CREATE TABLE "Business"
(
    "id"                    UUID PRIMARY KEY,
    "type"                  VARCHAR(32) NOT NULL,
    "taxId"                 VARCHAR(12) NOT NULL UNIQUE,
    "creationTime"          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    "ogrn"                  VARCHAR(15),
    "registeredName"        TEXT,
    "leKpp"                 VARCHAR(9),
    "registeredAddress"     TEXT,
    "registeredDate"        DATE,
    "registrationStatus"    VARCHAR(32),
    "legalEvents"           JSONB,
    "primaryOkvedCode"      VARCHAR(8),
    "additionalOkvedCodes"  JSONB,
    "leFormationMethodName" TEXT,
    "leParticipantsCount"   INTEGER,
    "leHasPredecessors"     BOOLEAN,
    "leHasSuccessors"       BOOLEAN,
    "ieSex"                 VARCHAR(16),
    "ieCitizenshipKind"     VARCHAR(16),
    "ieCountryCodeRaw"      TEXT,
    "location"              JSONB,
    "mspIncludedOn"         DATE,
    "mspCategory"           VARCHAR(16),
    "mspAverageHeadcount"   INTEGER,
    "mspReportYear"         INTEGER,
    "mspNewlyRegistered"    BOOLEAN,
    "mspSocialEnterprise"   BOOLEAN,
    "mspSnapshotOn"         DATE,
    "products"              JSONB,
    "partnerships"          JSONB,

    CONSTRAINT "BusinessTypeCheck" CHECK (
        "type" IN ('INDIVIDUAL_ENTREPRENEUR', 'LEGAL_ENTITY')
        ),
    CONSTRAINT "BusinessRegistrationStatusCheck" CHECK (
        "registrationStatus" IN (
                                 'ACTIVE', 'REORGANIZING', 'TERMINATING', 'TERMINATED', 'OTHER'
            )
        ),
    CONSTRAINT "BusinessMspCategoryCheck" CHECK (
        "mspCategory" IN ('MICRO', 'SMALL', 'MEDIUM')
        ),
    CONSTRAINT "BusinessMspAverageHeadcountCheck" CHECK (
        "mspAverageHeadcount" >= 0
        ),
    CONSTRAINT "BusinessMspReportYearCheck" CHECK (
        "mspReportYear" > 0
        ),
    CONSTRAINT "BusinessAdditionalOkvedCodesCheck" CHECK (
        jsonb_typeof("additionalOkvedCodes") = 'array'
            AND NOT jsonb_path_exists(
                "additionalOkvedCodes",
                '$[*] ? (@.type() != "string" || !(@ like_regex "^.{0,8}$" flag "s"))'
                    )
        ),
    CONSTRAINT "BusinessTaxIdFormatCheck" CHECK (
        ("type" = 'INDIVIDUAL_ENTREPRENEUR' AND "taxId" ~ '^[0-9]{12}$')
            OR ("type" = 'LEGAL_ENTITY' AND "taxId" ~ '^[0-9]{10}$')
        ),
    CONSTRAINT "BusinessOgrnFormatCheck" CHECK (
        "ogrn" IS NULL
            OR ("type" = 'LEGAL_ENTITY' AND "ogrn" ~ '^[0-9]{13}$')
            OR ("type" = 'INDIVIDUAL_ENTREPRENEUR' AND "ogrn" ~ '^[0-9]{15}$')
        ),
    CONSTRAINT "BusinessLeFieldsCheck" CHECK (
        "type" = 'LEGAL_ENTITY'
            OR (
            "leKpp" IS NULL
                AND "leFormationMethodName" IS NULL
                AND "leParticipantsCount" IS NULL
                AND "leHasPredecessors" IS NULL
                AND "leHasSuccessors" IS NULL
            )
        ),
    CONSTRAINT "BusinessIeFieldsCheck" CHECK (
        "type" = 'INDIVIDUAL_ENTREPRENEUR'
            OR (
            "ieSex" IS NULL
                AND "ieCitizenshipKind" IS NULL
                AND "ieCountryCodeRaw" IS NULL
            )
        ),
    CONSTRAINT "BusinessLeParticipantsCountCheck" CHECK ("leParticipantsCount" >= 0),
    CONSTRAINT "BusinessIeSexCheck" CHECK ("ieSex" IN ('MALE', 'FEMALE')),
    CONSTRAINT "BusinessIeCitizenshipKindCheck" CHECK (
        "ieCitizenshipKind" IN ('RUSSIAN', 'FOREIGN', 'STATELESS')
        )
);

CREATE TABLE "BusinessAccess"
(
    "id"         UUID PRIMARY KEY,
    "userId"     UUID        NOT NULL REFERENCES "User" ("id") ON DELETE CASCADE,
    "businessId" UUID        NOT NULL REFERENCES "Business" ("id") ON DELETE CASCADE,
    "role"       VARCHAR(16) NOT NULL,

    CONSTRAINT "BusinessAccessUserBusinessUnique" UNIQUE ("userId", "businessId"),
    CONSTRAINT "BusinessAccessRoleCheck" CHECK ("role" IN ('OWNER', 'ADMIN', 'VIEWER'))
);

CREATE INDEX "BusinessAccessBusinessIdIndex" ON "BusinessAccess" ("businessId");
