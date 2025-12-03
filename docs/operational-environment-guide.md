# 운영 환경 구성 가이드

> 본 문서는 `etc/project.md` 10장 "운영 환경 구성 가이드"의 내용을 발췌·확장한 운영 가이드이다. 정책 변경 시 반드시 `etc/project.md`를 우선 갱신하고, 본 문서 역시 동일하게 업데이트한다.

## 1. 기본 원칙 및 범위

- 멀티 AZ, 멀티 인스턴스 전제를 기준으로 고가용성(HA)과 무중단 배포를 달성한다.
- 모든 인프라는 IaC(Terraform/Helm)로 선언형 관리하며, CI/CD(GitHub Actions → ArgoCD/Flux) 파이프라인으로 배포한다.
- Redis/DB 장애 시 애플리케이션은 Allow All Fallback을 유지하되, 메트릭·알람으로 이상을 신속히 감지한다.

## 2. 인프라 토폴로지

- 네트워크: 최소 2개 AZ의 프라이빗 서브넷. 외부 트래픽은 L7 로드밸런서(ALB/Nginx Ingress) → 애플리케이션으로 진입.
- 애플리케이션: 컨테이너 기반(K8s: EKS/GKE/AKS) 배포를 기본. VM 기반 시에도 무상태 인스턴스 3개 이상 + 오토스케일링 적용.
- 데이터 계층: PostgreSQL(Primary + Sync Replica + Monitor) 멀티 AZ 구성. Redis Cluster(3 Shard, 각 Shard master+replica) AZ 분산.

## 3. 필수 인프라 스펙 권장치

- 애플리케이션
  - JDK 17, Spring Boot 3.x. 초기 스펙: vCPU 2~4, 메모리 4~8GB, 최소 3개 Pod/VM.
  - HPA 기준: CPU 60% 또는 P95 지연 15ms 초과 시 Scale-out, 최대 10개. JVM GC는 G1, `-XX:MaxGCPauseMillis=200` 권장.
- Redis
  - 7.x Cluster, Shard 3개(초기), 각 Shard master 1 + replica 1. 노드당 8GB 메모리(실사용 60% 이하), CPU 2~4 vCPU.
  - 영속성: AOF everysec + 주간 RDB 스냅샷. 카운터 유실 시 Allow All로 운영하며, 월 Quota 보정을 위해 AOF 유지.
  - 커넥션: 애플리케이션당 200~300 상한, Lettuce 풀은 2×CPU 수준. 모니터링: Ops/sec, Avg Latency, Evicted Keys, 메모리 파편화.
- PostgreSQL
  - 14 이상. 초기 스펙: vCPU 4, 메모리 16GB, 스토리지 200GB(Provisioned IOPS 6k+). Primary 1 + Sync Replica 1, 필요 시 Async Read Replica 1.
  - 저장 전략: WAL 보존 7일 + PITR, 일/주간 스냅샷. `max_connections` 500 이하, Hikari 풀 총합이 이를 넘지 않도록 환경별 상한 관리.
  - 인덱스/쿼리: ApiPolicy, UsageSnapshot 인덱스 배포 전 VACUUM/ANALYZE. Autovacuum 파라미터는 관측 기반으로 조정.

## 4. 네트워크·보안

- 데이터 계층은 프라이빗 서브넷에만 노출하고, LB→App→Redis/DB 간 최소 포트만 허용하는 Security Group/Firewall 정책을 적용한다.
- TLS: 외부는 LB 종단, 내부도 mTLS 또는 TLS-ALPN 활성화. Redis/PostgreSQL 모두 TLS 및 인증서 검증 필수.
- Secret 관리: 외부 Secret Manager(KMS 연계) 사용, 주입 시점 최소 노출. 감사 로그는 Admin API, 정책 변경, 토큰 회수 이벤트 전부 기록.

## 5. 스케일링 전략

- 애플리케이션
  - 무상태 유지, 세션/캐시는 Redis 등 외부 스토어 사용.
  - 내부 Rate Limit 체크 API 목표: P95 10ms, P99 20ms. 부하 테스트 지표를 HPA 보조 지표로 활용.
  - 배포: Rolling/Blue-Green으로 Zero-downtime, 실패 시 자동 롤백.
- Redis
  - Resharding 대비 메모리 40% 이하 사용을 유지. 키 패턴은 마이그레이션 없이도 Resharding 가능한 형태 유지.
  - 장애 시 Replica 승격 자동화(Sentinel/Cluster Manager). 카운터 유실 대비 애플리케이션은 Allow All Fallback 유지, 메트릭 알람 강화.
- PostgreSQL
  - 읽기 증가는 Read Replica로 분산, 쓰기 증가는 스케일업 → 샤딩/파티셔닝 순으로 확장.
  - 커넥션 풀 상한: prod 80~120, stage 40~60 등 환경별로 관리하고, 리밸런싱 시 풀 재구성 절차를 명시.

## 6. 백업·DR·운영 절차

- Redis: AOF 손상 시 RDB 스냅샷 복구 + 애플리케이션 Fallback(Allow All)로 서비스 지속. 복구 후 Lua 스크립트 로드/TTL 정상화 검증.
- PostgreSQL: WAL 기반 PITR, 일/주간 풀 백업 30일 보존, DR 리전에 비동기 복제. 목표 RPO 5분, RTO 15분. 분기별 Failover 리허설.
- 애플리케이션: 배포 파이프라인 헬스체크 실패 시 자동 롤백. Rate Limit 체크 API 타임아웃 10ms 유지로 다운스트림 영향 최소화.

## 7. 모니터링·알람

- 필수 메트릭: Rate Limit 허용/차단 수, Redis Ops/sec·Latency·Evicted Keys, PostgreSQL Replication Lag·Active Connections·Lock Wait, 애플리케이션 P95/P99 Latency·에러율.
- 알람 예시: Redis 평균 지연 5ms 초과 5분 지속, Evicted Keys 발생, PostgreSQL Replication Lag 30초 초과, 애플리케이션 5xx 비율 1% 초과.
- 대시보드: 테넌트별 트래픽/차단 비율, API Path별 사용량, 스케줄러(UsageSnapshot) 지연, Redis/DB 리소스 사용량 추이.

## 8. 환경별 가이드

- Dev: 단일 AZ, 단일 Redis/DB(비클러스터) 가능하나 키/쿼리/정책 모델은 운영과 동일 유지.
- Stage: 운영과 동일한 토폴로지(축소 스펙)로 성능/장애 리허설 수행, 부하 테스트는 Stage에서 진행.
- Prod: 멀티 AZ 이중화, TLS/mTLS 필수, IaC 변경만 반영. 장애 전파·Failover·데이터 복구 매뉴얼 및 알람 수신자 명시.
