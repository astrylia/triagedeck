CREATE TABLE invitation
(
    id          uuid PRIMARY KEY     DEFAULT uuidv7(),
    org_id      uuid        NOT NULL,
    email       text        NOT NULL CHECK ( email = lower(email) ),
    -- 不能邀请别人当 OWNER：OWNER 只能在创建组织时产生
    role        text        NOT NULL CHECK ( role IN ('ADMIN', 'AGENT', 'CUSTOMER') ),
    -- 只存 token 的 SHA-256 哈希，数据库泄露也拿不到能用的邀请链接
    token_hash  text        NOT NULL,
    invited_by  uuid        NOT NULL,
    expires_at  timestamptz NOT NULL,
    -- 为空表示还没被接受；接受后填上时间，同一个链接就不能再用
    accepted_at timestamptz,
    created_at  timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT fk_invitation_org FOREIGN KEY (org_id) REFERENCES organization (id),
    CONSTRAINT fk_invitation_invited_by FOREIGN KEY (invited_by) REFERENCES app_user (id),
    CONSTRAINT uq_invitation_token_hash UNIQUE (token_hash)
);

CREATE INDEX idx_invitation_org_id ON invitation (org_id);
