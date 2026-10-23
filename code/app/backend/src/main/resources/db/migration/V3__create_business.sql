CREATE TABLE business
(
    id                       UUID PRIMARY KEY,
    type                     VARCHAR(32) NOT NULL,
    tax_id                   VARCHAR(12) NOT NULL UNIQUE,
    creation_time            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ogrn                     VARCHAR(15),
    name                     TEXT,
    le_kpp                   VARCHAR(9),
    address                  TEXT,
    le_formation_method_name TEXT,
    le_participants_count    INTEGER,
    le_has_predecessors      BOOLEAN,
    le_has_successors        BOOLEAN,
    ie_sex                   VARCHAR(16),
    ie_citizenship_kind      VARCHAR(16),
    ie_country_code_raw      TEXT,
    location                 JSONB,
    msp_included_on          DATE,
    products                 JSONB,
    partnerships             JSONB,
    registry_fetched_at      TIMESTAMPTZ,
    msp_fetched_at           TIMESTAMPTZ,
    CONSTRAINT business_type_check CHECK (
        type IN ('INDIVIDUAL_ENTREPRENEUR', 'LEGAL_ENTITY')
        ),
    CONSTRAINT business_tax_id_format_check CHECK (
        (type = 'INDIVIDUAL_ENTREPRENEUR' AND tax_id ~ '^[0-9]{12}$')
            OR (type = 'LEGAL_ENTITY' AND tax_id ~ '^[0-9]{10}$')
        ),
    CONSTRAINT business_ogrn_format_check CHECK (
        ogrn IS NULL
            OR (type = 'LEGAL_ENTITY' AND ogrn ~ '^[0-9]{13}$')
            OR (type = 'INDIVIDUAL_ENTREPRENEUR' AND ogrn ~ '^[0-9]{15}$')
        ),
    CONSTRAINT business_le_fields_check CHECK (
        type = 'LEGAL_ENTITY'
            OR (
            le_kpp IS NULL
                AND le_formation_method_name IS NULL
                AND le_participants_count IS NULL
                AND le_has_predecessors IS NULL
                AND le_has_successors IS NULL
            )
        ),
    CONSTRAINT business_ie_fields_check CHECK (
        type = 'INDIVIDUAL_ENTREPRENEUR'
            OR (
            ie_sex IS NULL
                AND ie_citizenship_kind IS NULL
                AND ie_country_code_raw IS NULL
            )
        ),
    CONSTRAINT business_le_participants_count_check CHECK (le_participants_count >= 0),
    CONSTRAINT business_ie_sex_check CHECK (ie_sex IN ('MALE', 'FEMALE')),
    CONSTRAINT business_ie_citizenship_kind_check CHECK (
        ie_citizenship_kind IN ('RUSSIAN', 'FOREIGN', 'STATELESS')
        )
);

CREATE TABLE business_access
(
    id          UUID PRIMARY KEY,
    user_id     UUID        NOT NULL REFERENCES "user" (id) ON DELETE CASCADE,
    business_id UUID        NOT NULL REFERENCES business (id) ON DELETE CASCADE,
    role        VARCHAR(16) NOT NULL,
    CONSTRAINT business_access_user_business_unique UNIQUE (user_id, business_id),
    CONSTRAINT business_access_role_check CHECK (role IN ('OWNER', 'ADMIN', 'VIEWER'))
);

CREATE INDEX business_access_business_id_idx ON business_access (business_id);
