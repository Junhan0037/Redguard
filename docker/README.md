# Redis 로컬 개발 환경

이 폴더의 `docker-compose.yml`로 Redis(+Redis Insight)를 빠르게 띄울 수 있습니다. 기본 비밀번호는 `redguard`이며 `REDIS_PASSWORD` 환경변수로 변경할 수 있습니다.

## 사전 준비
- Docker Engine 및 Docker Compose v2 설치

## 실행
```bash
cd docker
# 필요 시 비밀번호 지정
export REDIS_PASSWORD=your-strong-password
# 백그라운드 실행
docker compose up -d
```

## 종료 및 정리
```bash
cd docker
docker compose down          # 컨테이너만 정지
docker compose down -v       # 데이터 볼륨(redguard-redis-data)까지 삭제
```

## 접속 정보
- Redis: `localhost:6379`, 비밀번호 `REDIS_PASSWORD`(기본 redguard)
- Redis Insight(UI): http://localhost:5540
  - 처음 접속 후 `Add Redis Database`에서 Host: `redis`, Port: `6379`, Password: `REDIS_PASSWORD` 입력
- PostgreSQL: `localhost:5432`, DB/USER/PASSWORD 모두 기본 `redguard` (또는 환경변수로 설정)
  - Flyway 11.7.x와 호환되는 `postgres:16.3` 이미지를 사용합니다.

## 애플리케이션 설정 예시
`application-local.yml` 등에서 다음과 같이 지정합니다.
```yaml
spring:
  data:
    redis:
      host: localhost
      port: 6379
      password: ${REDIS_PASSWORD:redguard}
      timeout: 1s
  datasource:
    url: jdbc:postgresql://localhost:5432/${POSTGRES_DB:redguard}
    username: ${POSTGRES_USER:redguard}
    password: ${POSTGRES_PASSWORD:redguard}
    driver-class-name: org.postgresql.Driver
    hikari:
      maximum-pool-size: 10
```

## 운영 고려 사항
- 보안을 위해 로컬 외부 노출 포트를 열 필요가 없으면 `ports` 항목을 제거하거나 `127.0.0.1:6379:6379` 형태로 제한하세요.
- 장기 실행 시 AOF 크기 증가를 감안해 주기적 `BGREWRITEAOF`가 동작하도록 기본 설정(`--appendfsync everysec`)을 유지했습니다.
- PostgreSQL 데이터 손상이 감지될 경우(`invalid checkpoint record` 등) 기존 볼륨을 초기화해야 합니다:
  ```bash
  cd docker
  docker compose down -v                # 모든 볼륨 포함 정리
  # 또는 문제가 있는 볼륨만 삭제
  docker volume rm redguard-postgres-data
  docker compose up -d
  ```
- Flyway가 `Unsupported Database: PostgreSQL 16.10` 오류를 낼 경우, 위와 같이 `redguard-postgres-data` 볼륨을 삭제한 뒤 `postgres:16.3` 이미지로 재기동하세요.
