# Redis Event Platform

> 대규모 트래픽 환경에서 동시성, 정합성, 처리량과 장애 복구를 검증하는 Redis 기반 이벤트 플랫폼.

**상태:** 초기 골격 · **목표 환경:** Rocky Linux 온프레미스 실습 · **구조:** Gradle 멀티모듈, API/Worker 독립 실행 목표

현재는 **단일 API 앱 + Redis + PostgreSQL** 연결 기반만 구현되어 있다. 다음 목표는 **MySQL 8 전환 → API/Worker 분리 → 확장·장애 복구 검증**이다. 도메인 API, Worker, MySQL, 운영 배포는 아직 구현하지 않았다. [공유 로드맵](docs/roadmap/infrastructure-reliability-roadmap.md)과 [현재 구현 차이 분석](docs/roadmap/implementation-gap-analysis.md)을 기준으로 단계별로 진행한다.

## 로컬 개발 시작 (현재 PostgreSQL 구성)

필수 환경: **Eclipse Temurin JDK 25**, Docker Engine/Desktop와 Compose v2.
Gradle은 Wrapper로 실행한다(9.8.2). Spring Boot는 4.0.9로 고정했다.
Redis는 **8.2.11**을 기준으로 하며 Compose와 Testcontainers 모두 `redis:8.2.11-alpine`을 사용한다.
소스 분석은 [Redis 8.2.11 태그](https://github.com/redis/redis/tree/8.2.11)를 기준으로 하고, 실험 결과에는 실제 소스 커밋 SHA와 이미지 digest를 기록한다.

```bash
cp .env.example .env
# .env 값을 변경했다면 앱 실행 셸에도 적용한다.
set -a
source .env
set +a
docker compose up -d --wait
./gradlew :apps:event-api:bootRun
```

- 상태 확인: `curl -fsS http://localhost:8080/actuator/health`
- 준비 상태(DB/Redis 연결 포함): `curl -fsS http://localhost:8080/actuator/health/readiness`
- 메트릭: `http://localhost:8080/actuator/prometheus`
- 앱 종료: `Ctrl+C`, 인프라 종료: `docker compose down` (데이터 볼륨 유지)
- IDE에서는 루트 `settings.gradle`을 Gradle 프로젝트로 열고 SDK/Gradle JVM을 JDK 25로 지정한다.
- `.env`는 Spring Boot가 자동 로드하지 않는다. 셸 환경 변수나 IDE 실행 설정으로 전달한다.
- Compose의 로컬용 자격 증명과 모니터링 노출 설정은 운영 배포 전에 변경한다.

```bash
./gradlew build                                # 컴파일, 단위 테스트, 실행 JAR 생성
./gradlew :apps:event-api:integrationTest       # Docker 필수: 실제 PostgreSQL/Redis 연결 검증
java -jar apps/event-api/build/libs/event-api-0.0.1-SNAPSHOT.jar
```

통합 테스트는 임시 컨테이너를 사용하며 로컬 Compose를 실행할 필요가 없다. CI에서는 두 명령을 모두 실행한다.
현재 도메인 API/테이블은 없으며, 스키마 변경은 첫 기능 구현 시 마이그레이션 도구로 관리한다.
초기 구조는 [ADR 0001](docs/adr/0001-initial-project-structure.md), MySQL 전환과 API/Worker 분리 방향은 [ADR 0002](docs/adr/0002-infrastructure-reliability-direction.md)에 기록했다. 실행 명령은 현재 코드 기준이며 MySQL 전환 후 함께 갱신한다.

CI와 동일한 검증: `./gradlew build :apps:event-api:integrationTest --no-daemon`. 영속 볼륨을 삭제하는 `docker compose down -v`는 데이터 보존이 필요한 환경에서 사용하지 않는다.

## 1. 프로젝트 소개

한정 수량 이벤트에 사용자가 몰리는 상황을 가정해, **쿠폰 발급과 대기열**을 중심으로 호출 제한, 메시징, 멱등성·캐시, 실시간 랭킹을 단계적으로 구축한다. 단순한 기능 구현보다 **문제 정의 → 설계 → 구현 → 부하 테스트 → 장애 재현 → 개선**의 반복에 초점을 둔다.

### 목표
- 동시 요청에서도 재고 초과 발급과 사용자 중복 발급을 방지한다.
- 대기열의 입장 정책과 발급 순서의 차이를 명확히 정의하고 검증한다.
- Redis Streams 기반 비동기 처리와 MySQL 8 영속화의 정합성을 검증한다.
- API와 Worker를 독립 JVM·컨테이너로 실행하고 각각 재시작·배포한다.
- MySQL Primary–Replica 비동기 복제, 백업·복원, 수동 Failover의 RTO/RPO를 측정한다.
- Redis 자료구조별 메모리 사용량, 처리량, p95/p99 지연을 측정한다.
- Rocky Linux 환경에 서비스를 배포하고, 부하 및 장애 상황에서 운영 가능성을 검증한다.

> **범위:** 대규모 동시 사용자 수는 부하 테스트의 *목표 시나리오*이며 현재 달성한 성능 수치가 아니다. 현재는 단일 애플리케이션 골격이며, 이후 단계에서 API와 Worker를 분리한다. 같은 호스트의 컨테이너 분리는 물리 서버 장애 격리를 보장하지 않는다.

## 2. 핵심 모듈 (5개, 기능 구현 예정)

| 모듈 | 포함 기능 | 주요 Redis 기술 | 핵심 검증 |
|---|---|---|---|
| **01. Event** | 선착순 쿠폰 발급 + 티켓팅 대기열 | String, Set, Sorted Set, Lua, TTL | 재고 정확성, 중복 방지, 대기열 순위/입장 성능 |
| **02. Traffic Control** | API Rate Limiter | INCR, EXPIRE, Lua, Sorted Set | Fixed/Sliding Window, Token Bucket 비교 |
| **03. Messaging** | 실시간 채팅 + 발급/대기 알림 | Pub/Sub, Streams, Consumer Group | 메시지 유실, 재처리, 느린 소비자 대응 |
| **04. Consistency & Cache** | 멱등성 처리 + 조회 캐시 | SET NX, Hash, TTL, Eviction | 장애 중 멱등성, Stampede, Hot Key, 메모리 |
| **05. Leaderboard** | 실시간 점수·순위 | Sorted Set, ZINCRBY, ZRANGE, ZRANK | 대규모 순위 조회/갱신, 메모리 |

### 01. Event — 쿠폰 발급 및 대기열
- 이벤트/쿠폰 생성, 수량 및 발급 기간 설정
- `ZADD`, `ZRANK` 기반 대기 등록 및 순위 조회
- 입장 허가 토큰과 동시 입장 인원 제어
- Lua 기반 중복 검사·재고 차감·발급 이벤트 기록의 원자적 처리(동일 Redis 실행 범위 내)
- 발급 상태: `WAITING → ADMITTED → PROCESSING → CONFIRMED / FAILED / EXPIRED`
- 재고 초과 발급 0건, 동일 이벤트·사용자 중복 확정 0건 검증

**설계 쟁점:** 대기 순서와 최종 발급 순서는 자동으로 같아지지 않는다. 엄격한 선착순 보장 여부 및 입장 후 타임아웃 정책을 문서화한다.

### 02. Traffic Control — 호출 제한
- 사용자/IP/API 단위 제한 정책
- Fixed Window, Sliding Window, Token Bucket 구현·비교
- `429 Too Many Requests`, `Retry-After` 응답
- Redis 장애 시 fail-open/fail-closed 정책을 API별로 정의

### 03. Messaging — 채팅 및 알림
- WebSocket 채팅, SSE 발급 상태 알림
- Pub/Sub: 접속 중인 사용자 대상 저지연 전달
- Streams: 재처리가 필요한 발급 이벤트와 비동기 작업
- `XREADGROUP`, `XACK`, `XPENDING`, `XAUTOCLAIM` 기반 소비자 복구
- DB 커밋 확인 후 `XACK` 및 최종 발급 확정 알림 전송
- 커밋 후 ACK 전 종료, Pending 회수, 재시도·백오프·장기 실패 메시지 처리 검증

**설계 쟁점:** Pub/Sub은 오프라인 메시지 보존을 보장하지 않는다. Streams도 소비자 중복 처리를 고려해 DB 유니크 제약과 멱등 처리를 병행한다.

### 04. Consistency & Cache — 멱등성 및 캐시
- 요청 멱등성 키와 `PROCESSING / SUCCEEDED / FAILED` 상태 관리
- 서버 종료·재시도·타임아웃 시 중복 비즈니스 처리 방지
- Cache Aside, TTL Jitter, Cache Stampede, Hot Key 대응
- 캐시와 멱등성 데이터의 Redis 인스턴스/메모리·Eviction 정책 분리 검토
- 금융 거래 유사 시나리오에서 Redis 키만으로 DB 트랜잭션의 exactly-once를 보장할 수 없음을 검증

### 05. Leaderboard — 실시간 랭킹
- 이벤트 참여 점수, 기간별 랭킹, TOP N, 개인 순위
- `ZINCRBY`, `ZREVRANGE`/`ZRANGE`, `ZREVRANK`/`ZRANK`
- 수백만 멤버의 갱신·조회·메모리 사용량 비교
- 대기열 Sorted Set과 랭킹 Sorted Set의 접근 패턴 비교

## 3. 목표 아키텍처

```mermaid
flowchart TD
    C[Client / k6] --> N[Nginx]
    N --> A1[API 1]
    N --> A2[API 2]
    A1 --> R[(Redis: Lua / ZSET / Streams)]
    A2 --> R
    R --> G[Consumer Group]
    G --> W1[Worker 1]
    G --> W2[Worker 2]
    W1 --> DB[(MySQL Primary)]
    W2 --> DB
    DB -->|비동기 복제| RP[(MySQL Replica)]
    A1 -. 지표 .-> M[Prometheus / Grafana]
    A2 -. 지표 .-> M
    W1 -. 지표 .-> M
    W2 -. 지표 .-> M
    R -. 지표 .-> M
    DB -. 지표 .-> M
    RP -. 지표 .-> M
```

위 구성은 **목표 논리 구성**이며 현재 구현 상태가 아니다. API의 Redis 예약·이벤트 기록과 Worker의 DB 확정 흐름은 후보 설계다. Redis 예약과 Stream 기록의 원자성, Redis–DB 사이의 비원자적 경계, 최종 발급 기록의 진실 공급원을 설계 단계에서 확정한다. DB 커밋 후 ACK하는 at-least-once 전달과 DB 유니크 제약·멱등 처리를 기본으로 하며, 커밋 후 알림의 유실·재전송 정책도 정의한다.

Replica는 Primary에서 복제받으며 Worker의 쓰기 대상이 아니다. 비동기 복제는 데이터 유실 가능성이 있고 두 DB 노드만으로 안전한 자동 Failover를 보장하지 않는다. 복제는 백업을 대체하지 않는다. Redis도 단일 인스턴스에서 시작하고 복제·Sentinel은 후속 장애 실험으로 다룬다.

## 4. 기술 스택 (계획)

| 영역 | 기술 |
|---|---|
| Backend | Java 25 LTS (Eclipse Temurin / HotSpot), Spring Boot 4.x, Spring Data Redis, Spring Data JPA |
| Data | Redis 8.2.11, MySQL 8 목표 (현재 PostgreSQL 17.6) |
| Realtime | SSE, WebSocket, Redis Pub/Sub, Redis Streams |
| Test | JUnit Jupiter (Spring Boot BOM), Testcontainers, k6 |
| OS / Network | Rocky Linux 9/10, systemd, firewalld, SELinux, Nginx |
| CI/CD / Provisioning | GitHub Actions, Docker 이미지/Registry, Ansible, Bash |
| Observability | Prometheus, Grafana, Redis INFO/LATENCY, Linux 진단 도구 |

개발·테스트·운영 환경의 JDK Vendor를 Eclipse Temurin으로 통일하고 Java 25 호환 Gradle 및 Spring Boot 4.x 버전을 사용한다.

Kafka와 Kubernetes는 초기 범위에서 제외한다. 기능별 마이크로서비스 분리도 실제 독립 배포 필요성이 생길 때 검토한다.

## 5. 개발 로드맵

단계를 한 번에 구현하지 않는다. 먼저 현황·영향 분석을 검토하고, 기반 전환부터 작은 변경으로 진행한다. 아래는 목표이며 분석 문서 작성만으로 Phase 0 완료를 의미하지 않는다.

| 단계 | 범위 | 완료 기준 |
|---|---|---|
| Phase 0 | 저장소 분석, PostgreSQL → MySQL 영향, 정합성·장애 지점, ADR와 PR 계획 | 검증 결과와 미검증 항목을 포함해 변경 범위·위험 설명 |
| Phase 1 | MySQL 8, 마이그레이션, 선착순 발급 MVP, 단일 API Compose | 클린 환경 실행 및 핵심 동작·실패 테스트 통과 |
| Phase 2 | 독립 Worker 앱·이미지, Streams, DB 커밋 후 ACK·멱등 처리 | Worker 독립 재시작과 중복 확정 방지 검증 |
| Phase 3 | API 2개, Nginx, Linux 프로세스·네트워크 관찰 | API 1개/2개 비교 실험 재현 |
| Phase 4 | Worker 2개, Pending 회수, 장애 주입 | 재처리·중복 방지 결과 입증 |
| Phase 5 | MySQL GTID 복제, 백업·복원, 수동 Failover | RTO/RPO와 데이터 대조를 포함한 Runbook 재현 |
| Phase 6 | GitHub Actions 이미지 생성·독립 배포, 관측성, Rocky Linux | 배포·헬스체크·롤백·관측 절차 재현 |
| Phase 7 | k6 성능 비교, 장애 결과와 병목·트레이드오프 분석 | 수치와 로그를 포함한 최종 보고서 |

호출 제한, 대기열, 채팅·알림, 캐시, 랭킹의 학습 범위는 유지한다. 세부 구현 순서는 쿠폰 MVP와 복구 경로를 우선해 각 단계에서 조정한다. Redis 내부 소스 분석도 자료구조·메모리·지연 실험과 연결한다. 상세 수용 기준은 [공유 로드맵](docs/roadmap/infrastructure-reliability-roadmap.md)을 참고한다.

## 6. 성능·정합성 실험

| 실험 | 가설/질문 | 측정 항목 |
|---|---|---|
| 동시 쿠폰 발급 | Lua 도입으로 초과 발급을 막을 수 있는가? | 성공/실패 수, 중복 수, TPS, p99 |
| 대기열 규모 | 10만/100만/300만 대기자에서 `ZRANK` 지연은? | p50/p95/p99, CPU, 메모리 |
| Rate Limiter | 알고리즘에 따라 정확성과 자원 사용량이 어떻게 달라지는가? | 거부율, p99, 키 수, 메모리 |
| Streams 복구 | 소비자 종료 후 미확인 이벤트를 복구할 수 있는가? | PEL, 재처리 건수, DB 누락/중복 |
| 캐시 부하 | TTL 동시 만료와 Hot Key가 병목을 만드는가? | DB QPS, 히트율, p99 |
| 랭킹 | 멤버 수 증가에 따른 조회·갱신 비용은? | 처리량, p99, bytes/member |
| Redis 장애 | AOF/복제/Failover에서 유실 가능 범위는? | RPO, RTO, 대조 결과 |
| 예약/발행 경계 장애 | 예약 후 이벤트 누락을 방지하거나 복구할 수 있는가? | 예약·이벤트·DB 대조, 복구 건수 |
| ACK 이전 Worker 장애 | DB 커밋 후 ACK 전 종료·재전달해도 안전한가? | Pending, 재처리 수, 중복 확정 0건 |
| Worker 확장·자원 경쟁 | Worker 1개/2개와 CPU 제한에 따른 영향은? | 처리량, API p99, 중복 확정 수 |
| MySQL 장애·복원 | 수동 승격·백업 복원이 가능한가? | 복제 지연, RTO/RPO, 데이터 대조 |
| 독립 재배포 | API와 Worker를 각각 재시작·배포할 수 있는가? | 헬스체크, 처리 중 작업 복구, 롤백 |
| API 확장 | 1대 → 2대 확장이 실제 처리량을 높이는가? | TPS, CPU, 네트워크, p99 |

**실험 원칙:** 하드웨어, Redis 버전, 데이터 크기, 연결 수, 동시 사용자, 부하 발생기, 워밍업, 테스트 시간, 부하 패턴, 반복 횟수를 기록한다. 결과를 측정하기 전에는 성능 개선 수치를 주장하지 않는다.

## 7. 저장소 구조

```text
redis-event-platform/
├── README.md
├── apps/
│   └── event-api/               # 초기에는 모듈형 단일 Spring Boot 앱
├── modules/
│   ├── event/                   # 쿠폰 발급 + 대기열
│   ├── traffic-control/         # Rate Limiter
│   ├── messaging/               # 채팅 + 알림
│   ├── consistency-cache/       # 멱등성 + 캐시
│   └── leaderboard/             # 실시간 랭킹
├── infrastructure/
│   ├── ansible/
│   ├── nginx/
│   ├── systemd/
│   └── monitoring/
├── load-tests/
├── experiments/
│   ├── concurrency/
│   ├── performance/
│   └── failure-recovery/
└── docs/
    ├── architecture/
    ├── adr/
    ├── roadmap/                # 공유 로드맵 및 구현 차이 분석
    ├── runbooks/
    └── results/
```

> `apps/event-api`와 5개 기능 모듈은 Gradle 멀티모듈로 구성했다. 기능 모듈과 인프라/실험 디렉터리는 초기 골격이며, `apps/event-worker`, `infrastructure/docker`, `infrastructure/mysql`은 해당 구현 단계에서 추가할 예정이다. 앱별 진입점·빌드 산출물을 분리하되, 기존 모듈 경계를 유지하고 최소한의 계약만 공유한다.

## 8. 완료 기준 (Definition of Done)

각 단계는 다음 조건을 만족해야 완료로 간주한다.

- [ ] 기능 요구사항과 예외·실패 시나리오 문서화
- [ ] 단위/통합/동시성 테스트 통과
- [ ] 재현 가능한 부하 테스트 스크립트 제공
- [ ] 측정 환경 및 결과(p50/p95/p99, TPS, 오류율) 기록
- [ ] 장애 시나리오와 복구 방법 문서화
- [ ] Redis 자료구조 및 명령 선택 근거와 트레이드오프 기록
- [ ] 배포·헬스체크·롤백 및 DB 마이그레이션 적용 순서 문서화
- [ ] 관련 API/Worker/Redis/MySQL/호스트·비즈니스 지표와 요청/이벤트 추적 ID 확인
- [ ] 변경 파일, 실행 명령, 실제 검증 결과, 미검증 항목·위험과 다음 단계 기록

## 9. 참고 자료

- [Redis data types](https://redis.io/docs/latest/develop/data-types/)
- [Redis Streams](https://redis.io/docs/latest/develop/data-types/stream/)
- [Redis streaming patterns](https://redis.io/docs/latest/develop/use-cases/streaming/)
- [Rocky Linux Documentation](https://docs.rockylinux.org/)

---

**Project principle:** *Build → Measure → Break → Recover → Explain.*
