-- 邮箱验证：验证通过时填上时间，NULL 表示还没验证过
ALTER TABLE app_user
    ADD COLUMN email_verified_at timestamptz;

CREATE TABLE email_verification_token
(
    id         uuid PRIMARY KEY     DEFAULT uuidv7(),
    user_id    uuid        NOT NULL,
    -- 和邀请、refresh token 一样，只存 SHA-256 哈希
    token_hash text        NOT NULL,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_email_verification_token_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT uq_email_verification_token_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_email_verification_token_user_id ON email_verification_token (user_id);
