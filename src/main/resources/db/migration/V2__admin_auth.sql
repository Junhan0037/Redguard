CREATE TABLE admin_user (
    id BIGSERIAL PRIMARY KEY,
    login_id VARCHAR(100) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    status VARCHAR(30) NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    locked_until TIMESTAMP,
    last_login_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_admin_user_login_id ON admin_user(login_id);
CREATE INDEX idx_admin_user_status ON admin_user(status);

CREATE TABLE admin_user_roles (
    admin_user_id BIGINT NOT NULL REFERENCES admin_user(id) ON DELETE CASCADE,
    role VARCHAR(30) NOT NULL
);

CREATE TABLE admin_audit_log (
    id BIGSERIAL PRIMARY KEY,
    actor_id BIGINT,
    action VARCHAR(100) NOT NULL,
    resource_type VARCHAR(50) NOT NULL,
    resource_id VARCHAR(100),
    payload_diff TEXT,
    ip VARCHAR(64),
    user_agent VARCHAR(512),
    occurred_at TIMESTAMP NOT NULL
);

CREATE INDEX idx_admin_audit_actor ON admin_audit_log(actor_id);
CREATE INDEX idx_admin_audit_action ON admin_audit_log(action);
CREATE INDEX idx_admin_audit_occurred_at ON admin_audit_log(occurred_at);
