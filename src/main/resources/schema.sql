-- 会话清单表。
CREATE TABLE IF NOT EXISTS chat_session (
    session_id VARCHAR(80) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    tenant_id VARCHAR(64),
    title VARCHAR(200),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (user_id, session_id)
);
DROP INDEX IF EXISTS idx_chat_session_owner;
CREATE INDEX IF NOT EXISTS idx_chat_session_owner_v2 ON chat_session(user_id, updated_at DESC);

-- 旧三元组主键迁移：同一账号同一会话多租户重复时保留最新一行，再换成 (user_id, session_id)。
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint c JOIN pg_class t ON c.conrelid = t.oid
        WHERE t.relname = 'chat_session' AND c.contype = 'p'
          AND pg_get_constraintdef(c.oid) LIKE '%tenant_id%'
    ) THEN
        DELETE FROM chat_session a USING chat_session b
        WHERE a.user_id = b.user_id AND a.session_id = b.session_id
          AND (a.updated_at < b.updated_at
               OR (a.updated_at = b.updated_at AND a.tenant_id < b.tenant_id));
        ALTER TABLE chat_session DROP CONSTRAINT chat_session_pkey;
        ALTER TABLE chat_session ADD PRIMARY KEY (user_id, session_id);
    END IF;
END $$;

-- 用户表。
CREATE TABLE IF NOT EXISTS app_users (
    username VARCHAR(64) PRIMARY KEY,
    password_hash TEXT NOT NULL,
    groups TEXT NOT NULL,
    tenant VARCHAR(64) NOT NULL,
    display_name VARCHAR(128),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS gender VARCHAR(16) DEFAULT '';
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS phone VARCHAR(32) DEFAULT '';
ALTER TABLE app_users ADD COLUMN IF NOT EXISTS avatar TEXT DEFAULT '';
CREATE UNIQUE INDEX IF NOT EXISTS uq_app_users_phone ON app_users(phone) WHERE phone <> '';

-- 组织表。
CREATE TABLE IF NOT EXISTS org_unit (
    org_key VARCHAR(64) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    parent_key VARCHAR(64),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE org_unit ADD COLUMN IF NOT EXISTS parent_key VARCHAR(64);

-- 用量表。
CREATE TABLE IF NOT EXISTS model_usage (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    user_id VARCHAR(64),
    model VARCHAR(128),
    prompt_tokens BIGINT DEFAULT 0,
    completion_tokens BIGINT DEFAULT 0,
    duration_ms BIGINT DEFAULT 0
);
ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS session_id VARCHAR(128);
ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS question TEXT;
ALTER TABLE model_usage ADD COLUMN IF NOT EXISTS tenant_id VARCHAR(64);
CREATE INDEX IF NOT EXISTS idx_model_usage_ts ON model_usage(ts DESC);
CREATE INDEX IF NOT EXISTS idx_model_usage_tenant_ts ON model_usage(tenant_id, ts DESC);

-- 模型配置表。
CREATE TABLE IF NOT EXISTS model_config (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(200) NOT NULL,
    type VARCHAR(20) NOT NULL CHECK (type IN ('CHAT','EMBEDDING')),
    base_url VARCHAR(500) NOT NULL,
    api_key VARCHAR(1000),
    model_name VARCHAR(200) NOT NULL,
    dimensions INTEGER,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    is_active BOOLEAN NOT NULL DEFAULT FALSE,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE model_config ADD COLUMN IF NOT EXISTS is_default BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS uk_model_active_embedding ON model_config(type) WHERE is_active = TRUE AND type = 'EMBEDDING';

-- 审计表。
CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMP NOT NULL,
    tenant_id VARCHAR(100) NOT NULL,
    user_id VARCHAR(100) NOT NULL,
    groups TEXT,
    action VARCHAR(200) NOT NULL,
    resource VARCHAR(500),
    detail TEXT,
    ip VARCHAR(100),
    status INT,
    duration_ms BIGINT,
    result VARCHAR(20)
);
CREATE INDEX IF NOT EXISTS idx_audit_tenant_ts ON audit_log(tenant_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_audit_user ON audit_log(user_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_audit_login_ts ON audit_log(tenant_id, ts DESC)
    WHERE action LIKE '%/api/auth/login%' OR action LIKE '%/api/auth/refresh%';

-- 密码重置申请表。
CREATE TABLE IF NOT EXISTS password_reset_request (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    code_hash TEXT,
    attempts INT NOT NULL DEFAULT 0,
    request_ip VARCHAR(64),
    requested_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    approved_by VARCHAR(64),
    approved_at TIMESTAMP,
    expires_at TIMESTAMP,
    used_at TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_prr_status ON password_reset_request(status);
CREATE INDEX IF NOT EXISTS idx_prr_user ON password_reset_request(username);

-- 权限节点表。
CREATE TABLE IF NOT EXISTS perm_node (
    code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(64) NOT NULL,
    node_type VARCHAR(16) NOT NULL DEFAULT 'menu',
    module VARCHAR(32) NOT NULL,
    description VARCHAR(256),
    builtin BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS parent_code VARCHAR(64) DEFAULT '';
ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS route_path VARCHAR(128) DEFAULT '';
ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS order_num INT DEFAULT 0;
ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;
ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS retired BOOLEAN DEFAULT FALSE;
ALTER TABLE perm_node ADD COLUMN IF NOT EXISTS icon VARCHAR(32) DEFAULT '';

-- 角色权限表。
CREATE TABLE IF NOT EXISTS role_permissions (
    role VARCHAR(64) NOT NULL,
    permission VARCHAR(64) NOT NULL,
    PRIMARY KEY (role, permission)
);
CREATE TABLE IF NOT EXISTS role_definitions (
    role VARCHAR(64) NOT NULL PRIMARY KEY,
    role_name VARCHAR(128) NOT NULL
);

-- 种子迁移版本表。
CREATE TABLE IF NOT EXISTS seed_migration (
    version VARCHAR(100) PRIMARY KEY,
    applied_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
