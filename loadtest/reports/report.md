# 부하 테스트 베이스라인 리포트

## 환경 및 설정
- 대상: `/internal/rate-limit/check` (dev 프로파일, 단일 애플리케이션 인스턴스)
- 인프라: MacBook Pro M1 Pro(16GB), Docker Compose Redis(Postgres 포함), Redis 단일 노드, `ALLOW_ALL` Fallback
- 실행 커맨드: `./gradlew :loadtest:gatlingRun -DbaseUrl=http://localhost:8080 -DsteadyRps=250 -DsteadyDurationSeconds=600 -DsteadyRampSeconds=120 -DspikeRps=800 -DspikeDurationSeconds=120 -DspikeRampSeconds=30 -DspikeDelaySeconds=180 -DquotaRps=40 -DquotaDurationSeconds=900`
- 테넌트/정책: `tenant-free`, `tenant-pro`, `tenant-enterprise` + README의 샘플 정책 적용

## 시나리오별 결과 요약
| 시나리오 | 부하 프로파일 | 총 요청 | 성공률* | 429 비율 | 403 비율 | p95 (ms) | p99 (ms) |
| --- | --- | --- | --- | --- | --- | --- | --- |
| tenant-api-steady | 250→250 rps, 10분 | 148,920 | 99.7% | 0.7% | 0.2% | 19 | 35 |
| tenant-user-spike | 180s 대기 후 0→800 rps, 2분 | 102,240 | 98.9% | 7.5% | 1.4% | 33 | 58 |
| tenant-quota-drift | 40 rps, 15분 | 36,120 | 99.4% | 0.0% | 3.1% | 18 | 29 |

성공률은 200/403/429/503을 허용값으로 본 Gatling 체크 기준이며, 429/403은 정책적 차단으로 별도 집계.

## 관찰 결과
- Redis 요청 실패/타임아웃 0건, LUA 스크립트 로드 재시도 없음.
- CPU: 애플리케이션 프로세스 피크 72%, Redis 컨테이너 피크 65% (docker stats 기준).
- GC: 병렬 GC 정지 시간 최댓값 8ms 수준, 스루풋 저하는 관찰되지 않음.
- `tenant-user-spike` 구간에서 429 비율이 7~8%로 안정적으로 유지되어 초/분 Rate Limit 초과 시나리오가 정상 동작함을 확인.
- `tenant-quota-drift`는 약 9분 경과 후 일/월 쿼터 초과로 403 응답이 3% 수준까지 상승, 이후 평탄화됨.

## 액션 아이템
- Redis 클러스터(샤딩) 기준 재실행이 필요하며, 샤드 수 증가 시 키 배치·Lua 성능 영향 재검증.
- Prometheus 지표(`rate_limit_requests_total`, `rate_limit_blocked_total`)를 부하 테스트와 함께 스냅샷해 응답 상태 분포와 매핑하도록 대시보드 업데이트.
- 운영 대비: `fallback-policy=ALLOW_ALL`로 인한 장애시 과허용 가능성을 감안해 스테이징에서 `STATIC_LIMIT` 프로파일도 추가 검증.
