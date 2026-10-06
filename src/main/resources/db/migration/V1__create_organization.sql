CREATE TABLE organization (
    id         uuid        PRIMARY KEY DEFAULT uuidv7(),
    name       text        NOT NULL,
    slug       text        NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT uq_organization_slug UNIQUE (slug)
);
