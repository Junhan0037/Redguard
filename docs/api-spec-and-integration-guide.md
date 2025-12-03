# API 명세 및 외부 연동 가이드

> 본 문서는 `etc/project.md`를 단일 소스 오브 트루스로 삼아 OpenAPI/Swagger 명세와 외부 연동 방법을 정리한 자료이다. API/도메인 변경 시 반드시 `etc/project.md`를 먼저 업데이트한 뒤 본 문서를 동기화한다.

## 1. 목적 및 범위

- 멀티테넌트 Rate Limiting & Quota 플랫폼의 내부/관리 API 사용법을 정의하고, 외부(게이트웨이/비즈니스 서비스)에서 안전하게 연동하는 방법을 안내한다.
- 범위: 내부 Rate Limit 체크 API, 백오피스(Admin) API(테넌트·Plan·ApiPolicy·사용량 조회·로그 조회), 인증 흐름, 공통 응답/에러 규약, 샘플 요청/응답, 연동 시 권장 타임아웃·재시도 정책.

## 2. OpenAPI/Swagger 제공 방식

- OpenAPI JSON: `GET /v3/api-docs` (환경별 Base URL 적용, 예: `https://api.{env}.redguard.internal/v3/api-docs`).
- Swagger UI: `GET /swagger-ui/index.html` (내부 네트워크 한정 노출, 운영 환경에서는 관리자 VPN/IP 화이트리스트 필수).
- 문서 버전 관리: Git 태그/릴리스 버전에 맞춰 `info.version`을 갱신하고, 주요 브레이킹 체인지 시 Change Log에 명시한다.

## 3. 공통 규약

- 인증/인가
  - Admin API: JWT Access Token(HS256, 15분) + Refresh Token(최대 24시간). Access Token JTI 블랙리스트 및 Refresh Token JTI 저장은 Redis 사용.
  - 내부 서비스 호출: mTLS + 서비스 간 클라이언트 크레덴셜(헤더 `X-Service-Auth` 또는 GW 단 배포된 인증서) 기반 신뢰.
- 헤더
  - `X-Request-Id`: 트레이싱 ID, 미지정 시 서버에서 발급.
  - `X-Tenant-Id`, `X-User-Id`: 게이트웨이가 파싱한 식별자 전달(내부 Rate Limit 체크 API에서는 Body 필드로도 포함).
  - `Accept-Language`: 기본 `ko-KR`, 필요 시 `en-US` 등 확장 가능.
- 응답/에러 포맷 (공통)

```json
{
  "code": "string",  // 비즈니스 에러 코드, 예: RATE_LIMIT_EXCEEDED
  "message": "string",  // 한국어 기본, 운영자 친화적 메시지
  "details": { "key": "value" },  // 추가 메타데이터(선택)
  "traceId": "string"  // 로깅/모니터링 연계용
}
```

- HTTP 상태 코드 규칙
  - 2xx: 성공
  - 400: 유효성 검증 실패, 잘못된 파라미터/스킴
  - 401/403: 인증/인가 실패, Quota 초과 시 403 `QUOTA_EXCEEDED`
  - 404: 리소스 없음
  - 409: 중복/경합(예: 이미 존재하는 요금제 이름)
  - 429: Rate Limit 초과 `RATE_LIMIT_EXCEEDED`
  - 5xx: 시스템 장애(최소화, 모니터링 필수)
- 버전 관리: URI 버전(v1) + OpenAPI `info.version` 병행. 브레이킹 체인지는 `/v2` 등 새 버전으로 추가 제공.
- Idempotency: 상태 변경성 API에는 `Idempotency-Key` 헤더를 지원하고, 키별 요청 결과를 최소 24시간 보존한다(운영 설정값 기준).

## 4. 주요 API 명세 (요약)

### 4.1 내부 Rate Limit 체크 API

- `POST /internal/rate-limit/check`
- 요청 바디 예시

```json
{
  "tenantId": "tenant-123",
  "userId": "user-456",  // 옵션
  "apiPath": "/v1/report/export",
  "httpMethod": "GET",
  "timestamp": "2024-05-01T12:34:56.789Z"
}
```

- 응답 바디 예시

```json
{
  "allowed": true,
  "reason": "OK",  // RATE_LIMIT_EXCEEDED | QUOTA_EXCEEDED | ALLOW_FALLBACK 등
  "remaining": {
    "perSecond": 10,
    "perMinute": 120,
    "perDay": 9500,
    "quotaDaily": 48000,
    "quotaMonthly": 980000
  },
  "resetAt": {
    "second": "2024-05-01T12:34:57Z",
    "minute": "2024-05-01T12:35:00Z",
    "day": "2024-05-02T00:00:00Z",
    "month": "2024-06-01T00:00:00Z"
  }
}
```

- 통신 정책: 게이트웨이/비즈니스 서비스는 이 API를 선행 호출 후 비즈니스 로직을 수행한다. 타임아웃 10ms, 재시도 없음, 실패 시 Allow All(설계 기본 정책)으로 처리하고 메트릭/로그로 추적한다.

### 4.2 Admin 인증 API

- `POST /admin/auth/login`: `loginId`, `password` 입력 → Access/Refresh Token, JTI 반환.
- `POST /admin/auth/refresh`: Refresh Token 유효 시 Access Token 재발급.
- `POST /admin/auth/logout`: Access Token 블랙리스트 등록 + Refresh Token 회수.
- 보안: 로그인 실패 N회(설정값) 시 계정 잠금, 비밀번호 정책은 최소 길이·복잡도·만료 규칙 적용.

### 4.3 테넌트 관리 API

- `POST /admin/tenants`: 테넌트 생성(기본 Plan 지정).
- `GET /admin/tenants/{id}` / `GET /admin/tenants`: 단건/목록 조회.
- `PUT /admin/tenants/{id}`: 이름/상태 수정.
- `PUT /admin/tenants/{id}/plan`: 요금제 변경(즉시 또는 특정 시점 적용 옵션).

### 4.4 Plan 관리 API

- `POST /admin/plans`: Plan 생성(초/분/일 Rate Limit, 일/월 Quota 포함).
- `GET /admin/plans/{id}` / `GET /admin/plans`: 조회.
- `PUT /admin/plans/{id}`: 수정.
- 검증: 이름 중복/기본 한도 유효성 검사, null 허용 필드는 명시적으로 처리.

### 4.5 ApiPolicy 관리 API

- `POST /admin/api-policies`: Plan/Tenant/Endpoint 오버라이드 정책 생성.
- `GET /admin/api-policies`: 필터 조회(테넌트별, Plan별, API별).
- `PUT /admin/api-policies/{id}` / `DELETE /admin/api-policies/{id}`: 수정/삭제.
- 우선순위: Tenant > Plan > Default, 설계서 7.3 규칙을 준수.

### 4.6 사용량/로그 조회 API

- `GET /admin/usage/daily` / `monthly`: 테넌트/유저/API별 일/월 사용량 조회(기간 필터 지원).
- `GET /admin/limit-hit-logs`: Rate Limit/Quota 초과 로그 조회(테넌트/유저/엔드포인트/기간 필터).

## 5. 외부 연동 가이드

- 게이트웨이 연동
  - 요청 전 `POST /internal/rate-limit/check`를 호출하고, `allowed=false`면 즉시 429/403 매핑 응답.
  - 헤더로 전달된 `X-Tenant-Id`, `X-User-Id`를 신뢰할 수 없는 경우 사전 인증/토큰 파싱으로 보강.
  - Circuit Breaker: Redis 장애로 인한 일시적 허용 증가 감지 시 알람 발송, 비즈니스 로그에 사유 기록.
- 비즈니스 서비스 연동
  - 내부 호출 시에도 동일한 체크 API 사용. 대량 배치/비동기 처리 시에도 사전 체크를 권장.
  - Idempotency-Key를 사용해 정책 변경/요금제 변경 API의 중복 호출을 방지.
- 관리 콘솔/자동화
  - Admin API 호출은 RBAC(Role: ADMIN/OPERATOR/VIEWER) 매트릭스를 준수. 자동화 계정은 제한된 스코프의 서비스 계정만 발급.

## 6. 샘플 시나리오

- API 게이트웨이 흐름
  1. JWT 또는 API Key에서 `tenantId`/`userId`/`scopes` 추출.
  2. `POST /internal/rate-limit/check` 호출(10ms 타임아웃, 재시도 없음).
  3. `allowed=true` → 비즈니스 API 호출, `allowed=false` → 429/403 매핑 후 응답.
  4. Trace ID와 Rate Limit 결과를 로그/메트릭으로 기록.
- 테넌트 요금제 변경
  1. 운영자가 Admin 콘솔에서 Plan 변경 요청 → `PUT /admin/tenants/{id}/plan` 호출.
  2. 성공 시 정책 캐시 무효화/Redis 적용 시점을 명시하고, 감사 로그에 기록.
  3. 변경 직후 Rate Limit 체크 API는 새 정책을 반영해 응답.

## 7. 변경 관리

- API 추가/변경 시
  - OpenAPI 스키마(`schemas`, `paths`) 갱신 → Pull Request에 스키마 변경 diff 포함.
  - 브레이킹 체인지 발생 시 `/v2` 등 새 버전 병행 배포 후 구버전 Deprecation 타임라인 공지.
- 문서 동기화
  - `etc/project.md` 변경 시 본 문서를 즉시 업데이트하고, 릴리스 노트/Change Log에 링크를 추가한다.

