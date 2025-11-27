CREATE TABLE plans (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(50) NOT NULL UNIQUE,
    description TEXT,
    rate_limit_per_second BIGINT,
    rate_limit_per_minute BIGINT,
    rate_limit_per_day BIGINT,
    quota_per_day BIGINT,
    quota_per_month BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE tenants (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(100) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL,
    plan_id BIGINT NOT NULL REFERENCES plans(id),
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE api_policies (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT REFERENCES tenants(id),
    plan_id BIGINT REFERENCES plans(id),
    http_method VARCHAR(10) NOT NULL,
    api_pattern VARCHAR(255) NOT NULL,
    description TEXT,
    rate_limit_per_second BIGINT,
    rate_limit_per_minute BIGINT,
    rate_limit_per_day BIGINT,
    quota_per_day BIGINT,
    quota_per_month BIGINT,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT chk_api_policy_target CHECK (tenant_id IS NOT NULL OR plan_id IS NOT NULL)
);

CREATE TABLE usage_snapshots (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    user_id VARCHAR(64),
    api_path VARCHAR(255),
    snapshot_date DATE NOT NULL,
    period_type VARCHAR(20) NOT NULL,
    total_count BIGINT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_usage_snapshot_scope UNIQUE (tenant_id, user_id, api_path, snapshot_date, period_type)
);

CREATE TABLE limit_hit_logs (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL REFERENCES tenants(id),
    user_id VARCHAR(64),
    api_path VARCHAR(255) NOT NULL,
    reason VARCHAR(20) NOT NULL,
    occurred_at TIMESTAMP WITH TIME ZONE NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_limit_hit_tenant_occurred_at
    ON limit_hit_logs (tenant_id, occurred_at);
