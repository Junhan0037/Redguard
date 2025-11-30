-- Reporting 및 대량 조회 최적화를 위한 인덱스 추가
-- 기존 limit_hit_logs 인덱스를 보다 최신 조회 패턴에 맞추기 위해 교체
DROP INDEX IF EXISTS idx_limit_hit_tenant_occurred_at;

CREATE INDEX idx_limit_hit_tenant_occurred_at_desc
    ON limit_hit_logs (tenant_id, occurred_at DESC, id DESC);

CREATE INDEX idx_limit_hit_tenant_user_occurred_at
    ON limit_hit_logs (tenant_id, user_id, occurred_at DESC, id DESC);

CREATE INDEX idx_limit_hit_tenant_api_occurred_at
    ON limit_hit_logs (tenant_id, api_path, occurred_at DESC, id DESC);

CREATE INDEX idx_usage_snapshot_tenant_period_date
    ON usage_snapshots (tenant_id, period_type, snapshot_date DESC, id DESC);

CREATE INDEX idx_usage_snapshot_tenant_user_period_date
    ON usage_snapshots (tenant_id, user_id, period_type, snapshot_date DESC, id DESC);

CREATE INDEX idx_usage_snapshot_tenant_api_period_date
    ON usage_snapshots (tenant_id, api_path, period_type, snapshot_date DESC, id DESC);
