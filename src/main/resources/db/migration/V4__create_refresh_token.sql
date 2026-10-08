CREATE TABLE refresh_token
(
    id         uuid PRIMARY KEY     DEFAULT uuidv7(),
    user_id    uuid        NOT NULL,
    -- 和邀请 token 一样，只存 SHA-256 哈希
    token_hash text        NOT NULL,
    expires_at timestamptz NOT NULL,
    -- 用过一次（换了新 token）或退出登录后填上时间，之后不能再用
    revoked_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_refresh_token_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uq_refresh_token_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_refresh_token_user_id ON refresh_token (user_id);
