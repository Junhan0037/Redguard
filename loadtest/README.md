# 시나리오 기반 부하 테스트 가이드

이 디렉터리는 `/internal/rate-limit/check` 엔드포인트에 대한 Gatling 기반 부하 테스트 스위트와 실행 절차를 제공합니다. 멀티테넌트 시나리오(테넌트/테넌트+유저/테넌트+API)를 분리해 정책 우선순위와 레이트 리밋·쿼터 동작을 동시에 검증하도록 설계했습니다.

## 사전 준비
- JDK 17, Gradle Wrapper(`./gradlew`)
- Docker로 Redis/PostgreSQL 기동: `docker/README.md` 참고
- 애플리케이션 기동: `spring.profiles.active=dev`로 실행하고 Redis/DB 연결 확인

## 테스트 데이터 준비(필수)
스테이징/로컬 DB에 다음과 같이 샘플 플랜·테넌트·API 정책을 추가합니다. 이미 존재하면 `ON CONFLICT DO NOTHING`으로 중복을 무시합니다.

```sql
-- 플랜 기본값
INSERT INTO plans (name, description, rate_limit_per_second, rate_limit_per_minute, rate_limit_per_day, quota_per_day, quota_per_month)
VALUES
    ('FREE', '무료 플랜', 20, 200, 5000, 20000, 200000),
    ('PRO', '프로 플랜', 50, 800, 30000, 200000, 1500000),
    ('ENTERPRISE', '엔터프라이즈 플랜', 150, 2000, 100000, 800000, 6000000)
ON CONFLICT (name) DO NOTHING;

-- 테넌트
INSERT INTO tenants (name, status, plan_id)
SELECT 'tenant-free', 'ACTIVE', id FROM plans WHERE name = 'FREE'
ON CONFLICT (name) DO NOTHING;

INSERT INTO tenants (name, status, plan_id)
SELECT 'tenant-pro', 'ACTIVE', id FROM plans WHERE name = 'PRO'
ON CONFLICT (name) DO NOTHING;

INSERT INTO tenants (name, status, plan_id)
SELECT 'tenant-enterprise', 'ACTIVE', id FROM plans WHERE name = 'ENTERPRISE'
ON CONFLICT (name) DO NOTHING;

-- 엔드포인트별 오버라이드 정책 예시
INSERT INTO api_policies (tenant_id, plan_id, http_method, api_pattern, description, rate_limit_per_second, rate_limit_per_minute, rate_limit_per_day, quota_per_day, quota_per_month)
SELECT t.id, NULL, 'POST', '/v1/report/export', '무료 테넌트 Export 강화 제한', 10, 100, 2000, 10000, 80000
FROM tenants t WHERE t.name = 'tenant-free'
ON CONFLICT DO NOTHING;

INSERT INTO api_policies (tenant_id, plan_id, http_method, api_pattern, description, rate_limit_per_second, rate_limit_per_minute, rate_limit_per_day, quota_per_day, quota_per_month)
SELECT NULL, p.id, 'GET', '/v1/report/detail/*', '플랜 공통 리포트 상세 제한', 80, 1200, 40000, 300000, 2500000
FROM plans p WHERE p.name = 'ENTERPRISE'
ON CONFLICT DO NOTHING;
```

## 실행 방법
```bash
# 스테이징/로컬 기준 기본 실행
./gradlew :loadtest:gatlingRun \
  -DbaseUrl=http://localhost:8080 \
  -DsteadyRps=250 -DsteadyDurationSeconds=600 -DsteadyRampSeconds=120 \
  -DspikeRps=800 -DspikeDurationSeconds=120 -DspikeRampSeconds=30 -DspikeDelaySeconds=180 \
  -DquotaRps=40 -DquotaDurationSeconds=900 \
  -DtenantFreeId=tenant-free -DtenantProId=tenant-pro -DtenantEnterpriseId=tenant-enterprise
```

- `baseUrl`: 게이트웨이/서비스 엔드포인트
- `steady*`: 일반 트래픽 시나리오 RPS/지속 시간/램프업
- `spike*`: 짧은 버스트(초/분 레이트리밋 초과) 트래픽
- `quota*`: 일/월 쿼터 소비 패턴
- 테넌트 식별자는 테스트 DB에 삽입한 값과 일치해야 합니다.

## 시나리오 구성
- `tenant-api-steady`: TENANT_API 스코프, 엔드포인트별 혼합 트래픽으로 플랜 기본값과 엔드포인트 오버라이드를 검증
- `tenant-user-spike`: TENANT_USER 스코프, 짧은 버스트로 초·분 단위 한도 초과 시 429/403 응답 비율 확인
- `tenant-quota-drift`: TENANT 스코프, 일정 RPS로 일/월 쿼터 소진 시나리오를 재현
- 공통 Assertion: 성공률 > 97%, P95 이하(실제는 P99.9도 Gatling 리포트에서 확인), 실패율 < 2%

## 결과 확인 및 리포트
- Gatling HTML 리포트 경로: `loadtest/build/reports/gatling/<simulation-id>/index.html`
- 실행 후 요약은 `loadtest/reports/<yyyymmdd>-*.md`에 기록합니다. 최신 베이스라인은 `loadtest/reports/report.md` 참고.
- 관찰 지표
  - HTTP 상태 분포: 200/403/429/503 비율
  - 응답 지연: p50/p95/p99, 최대값
  - Throughput: 시나리오별 RPS 및 총 요청 수
  - Redis/DB CPU·메모리(별도 모니터링 도구)와 애플리케이션 GC 로그

## 운영 시 주의사항
- 부하 테스트는 스테이징 전용 Redis/DB를 사용하고, 운영 트래픽과 격리된 네트워크에서 수행합니다.
- 테스트 전/후 Redis 카운터를 `SCAN`/`KEYS` 대신 `MATCH`+`SCAN`으로 점검해 잔여 키가 누수되지 않았는지 확인합니다.
- `redguard.rate-limit.fallback-policy`가 `ALLOW_ALL`인 경우 Redis 장애 시 허용 비율이 높게 나올 수 있으므로, 정책 값을 명시적으로 기록합니다.
