# RedGuard — 멀티테넌트 Rate Limit & Quota 플랫폼

![RedGuard.png](./images/RedGuard.png)

## 개요
- 테넌트/유저/엔드포인트 단위 초·분·일 Rate Limit와 월 Quota를 관리하는 Kotlin + Spring Boot 기반 서비스입니다.
- Redis 중앙 스토어(2-버킷 슬라이딩 윈도우 + Lua)로 멀티 인스턴스 환경에서 일관된 제한을 제공합니다.
- PostgreSQL + JPA로 정책/사용량/감사 로그를 관리하고, Admin RBAC와 JWT 기반 인증을 제공합니다.
- 설계의 단일 소스는 `etc/project.md`이며, 운영/연동 가이드는 `docs/` 및 `etc/` 문서를 참조합니다.

## 프로젝트 배경·목표
- B2B 멀티테넌트 환경에서 테넌트·유저·엔드포인트별로 다른 초/분/일 Rate Limit와 월 Quota를 적용해야 하는 요구를 해결합니다.
- 단순 Nginx rate_limit로는 멀티 AZ/멀티 인스턴스에서 일관성 유지가 어려워 Redis 중앙 스토어와 Lua 스크립트 기반 슬라이딩 윈도우 알고리즘을 채택했습니다.
- 목표: 초당 1,000 RPS 내외의 내부 Rate Limit 체크를 P95 10ms/P99 20ms 이하로 처리하고, 정책/로그/사용량을 백오피스에서 관리할 수 있게 합니다.

## 주요 용어
- Tenant: 서비스를 사용하는 고객(회사) 단위.
- User: 테넌트 소속 사용자 또는 API Client.
- Plan: 테넌트가 구독한 요금제(FREE/PRO/ENTERPRISE 등)로 기본 Rate Limit/Quota를 정의.
- Policy: Plan 기본값을 테넌트/엔드포인트 단위로 오버라이드하는 규칙 집합.
- Rate Limit: 짧은 시간(초/분/일) 단위 요청 제한.
- Quota: 일/월 단위 누적 사용량 한도.

## 주요 기능
- `/internal/rate-limit/check` 내부 API로 게이트웨이/백엔드에서 제한 여부를 선행 검증.
- 테넌트·Plan·ApiPolicy CRUD 및 정책 오버라이드, Limit 초과/사용량 조회 Admin API 제공.
- Redis 장애 시 Allow All Fallback 정책 및 관측(메트릭/로그) 기본 제공.
- UsageSnapshot 스케줄러로 Redis 카운터를 DB에 집계, Prometheus 메트릭/Actuator 노출.

## 아키텍처/패키지 개요
- 레이어드 구조: `api`(Controller/DTO/예외 처리), `application`(유스케이스/트랜잭션), `domain`(엔티티/도메인 규칙), `infrastructure`(JPA/Redis/Security/Job/Config).
- 핵심 구성
  - Rate Limit 엔진: `infrastructure/redis/*`(Lua 스크립트 실행, 키 빌더, Fallback 처리)
  - 정책 해석: `application/ratelimit/RateLimitPolicyResolver.kt`
  - 메트릭: `infrastructure/metrics/MicrometerRateLimitMetricsPublisher.kt`
  - Admin 인증/RBAC: `infrastructure/security/*`, `application/admin/*`
  - 스냅샷/배치: `infrastructure/job/UsageSnapshotScheduler.kt`

## 로컬 실행(Dev)
- 사전 요구: JDK 17, Docker(선택), Gradle Wrapper 사용.
- Redis/PostgreSQL 로컬 준비
  - Docker Compose: `cd docker && docker compose up -d` (Redis, Redis Insight, PostgreSQL 기동)
  - 기본 접속: Redis `localhost:6379` 비밀번호 `redguard`, Postgres `localhost:5432` DB/USER/PW `redguard`
- 애플리케이션 실행
  - `./gradlew bootRun --args='--spring.profiles.active=dev'`
  - Dev 프로필 기본 설정: `application-dev.yml`의 Redis/DB 정보와 Admin 토큰 시크릿(`REDGUARD_ADMIN_ACCESS_SECRET`, `REDGUARD_ADMIN_REFRESH_SECRET`) 환경변수 필요
- Flyway가 `src/main/resources/db/migration` 스크립트를 자동 적용하며, JPA `ddl-auto=validate` 기준으로 스키마를 검증합니다.

## 테스트
- 단위/통합 테스트: `./gradlew test`
- Redis 의존 테스트는 로컬 Redis 또는 Testcontainers 환경을 전제로 합니다(실제 Redis를 선호).

## 주요 엔드포인트(요약)
- 헬스체크: `GET /actuator/health`
- 메트릭: `GET /actuator/prometheus`
- Rate Limit 체크: `POST /internal/rate-limit/check`
- Admin 인증: `POST /admin/auth/login`, `/refresh`, `/logout`
- 테넌트/Plan/Policy 관리: `/admin/tenants`, `/admin/plans`, `/admin/api-policies`
- 사용량/로그 조회: `/admin/usage/daily|monthly`, `/admin/limit-hit-logs`

## 설정 및 프로필
- 기본 프로필: `dev` (`application.yml` → `application-dev.yml`)
- `staging`/`prod`: TLS 활성 Redis, 외부 제공 환경변수(`REDGUARD_DB_URL`, `REDGUARD_REDIS_HOST` 등)로 구성
- 공통 속성
  - `redguard.rate-limit.fallback-policy`: Redis 장애 시 기본 정책(기본 ALLOW_ALL)
  - `redguard.admin-auth.*`: JWT 발급/검증 시크릿, TTL, 실패 잠금 정책
  - `redguard.usage-snapshot.*`: 스냅샷 스케줄러 Cron/Lock/Batch 설정

## 문서/참조
- [운영 환경 가이드](docs%2Foperational-environment-guide.md)
- [API 명세/연동 가이드](docs%2Fapi-spec-and-integration-guide.md)
- [로컬 인프라 실행](docker%2FREADME.md)

## 지원되는 빌드/도구
- Spring Boot 3.5.x, Kotlin 2.0.x, Gradle Wrapper 포함
- Flyway, Micrometer(Prometheus), JPA + PostgreSQL, Redis(Lettuce), JWT(jjwt)
