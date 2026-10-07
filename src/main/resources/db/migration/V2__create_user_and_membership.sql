CREATE TABLE app_user
(
    id            uuid PRIMARY KEY     DEFAULT uuidv7(),
    email         text        NOT NULL CHECK ( email = lower(email) ),
    password_hash text        NOT NULL,
    name          text        NOT NULL,
    created_at    timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

CREATE TABLE membership
(
    user_id    uuid REFERENCES app_user (id),
    org_id     uuid REFERENCES organization (id),
    role       text        NOT NULL CHECK ( role IN ('OWNER', 'ADMIN', 'AGENT', 'CUSTOMER')),
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT pk_membership PRIMARY KEY (user_id, org_id)
);

CREATE INDEX idx_membership_org_id ON membership (org_id);