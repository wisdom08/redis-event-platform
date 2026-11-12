# 인프라·정합성·장애 복구 로드맵

> 문서 상태: 향후 구현 목표. 현재 코드의 완료 상태는 [구현 차이 분석](implementation-gap-analysis.md)을 참고한다.
>
> 원본: 로컬 `output/redis-event-platform-codex-roadmap.md`의 구현 방향과 수용 기준을 공유 문서로 정리했다.
>
> 대상 저장소: https://github.com/wisdom08/redis-event-platform\
> 핵심 기술: Java / Spring Boot / Gradle / Redis / **MySQL 8** / Docker
> Compose / Linux\
> 목적: Redis 기반 이벤트·선착순 발급 플랫폼을 유지하면서 실무형
> 운영·분산처리·장애복구 역량을 검증하는 프로젝트로 확장한다.

## 0. 적용 원칙

**이 문서는 구현 방향과 수용 기준을 정의한다. 저장소의 현재 상태를
확정한 명세가 아니다.**

1.  저장소의 `README`, `settings.gradle`, 각 모듈의 `build.gradle`,
    소스, 테스트, Docker/Compose, GitHub Actions, 문서를 먼저 조사한다.
2.  **현재 구현된 것 / 미구현된 것 / 이 문서와 충돌하는 것**을 구분해
    `docs/roadmap/implementation-gap-analysis.md`에 기록한다.
3.  현재 모듈과 패키지 구조를 최대한 보존한다. 이름이나 경로가 다르면
    실제 저장소에 맞춰 계획을 조정한다.
4.  데이터베이스는 **MySQL 8**을 목표로 한다. PostgreSQL 관련
    의존성·설정·마이그레이션이 있으면 영향도를 조사하고 안전하게
    교체한다. 데이터 손실 위험이 있는 변경은 자동 수행하지 않는다.
5.  모든 단계를 한 번에 구현하지 않는다. **Phase 0 분석 → Phase 1 기반
    정리**부터 시작하고, 각 단계가 끝나면 변경 파일, 실행 명령, 테스트
    결과, 남은 위험을 보고한다.
6.  외부 인프라 계정, 비밀키, 운영 서버 접근이 필요하면 임의로
    생성하거나 추측하지 말고 필요한 입력을 명시한다.
7.  설계 결정을 `docs/adr/`에 남기고, 실험은 재현 가능한 명령·측정
    환경·결과와 함께 기록한다.

## 1. 목표와 비목표

### 목표

-   API와 이벤트 처리 Worker를 **독립 JVM / 독립 Docker 컨테이너**로
    실행·재시작·배포한다.
-   Redis의 대기열, Lua 원자성, Streams Consumer Group, 멱등성 전략을
    실험한다.
-   MySQL 8 기반 트랜잭션·유니크 제약·복제·백업·장애 복구를 학습한다.
-   API 수평 확장, Nginx 로드밸런싱, Linux 프로세스/네트워크 관찰을
    실습한다.
-   GitHub Actions 기반 빌드·테스트·이미지 생성·배포 흐름을 구성한다.
-   Prometheus/Grafana 등으로 지연 시간, 처리량, 데이터 정합성, 장애
    복구를 관찰한다.
-   `Build → Measure → Break → Recover → Explain`을 프로젝트의 실험
    원칙으로 적용한다.

### 비목표 (초기)

-   Kubernetes, Kafka, 복잡한 마이크로서비스 분할
-   두 대의 DB만으로 자동 무중단 Failover를 보장한다고 주장하기
-   단순히 컨테이너 수를 늘려 고가용성이 확보됐다고 주장하기
-   성능 측정 없이 '정확히 한 번 처리' 또는 '무중단'을 선언하기
-   첫 단계에서 기존 도메인과 프로젝트 구조를 전면 재작성하기

## 2. 목표 논리 아키텍처

``` text
                    Client / k6
                        |
                  Nginx (LB)
                    /       \
              API #1       API #2
                    \       /
                       Redis
              (Lua / ZSET / Streams)
                         |
                  Consumer Group
                    /         \
               Worker #1    Worker #2
                    \         /
                    MySQL Primary
                         |
                 async replication
                         |
                    MySQL Replica

   API / Worker / Redis / MySQL / Host Metrics
                         |
                Prometheus + Grafana
```

**주의:** 도식은 논리 구성이다. API가 Redis에 예약/이벤트를 기록하고
Worker가 DB에 확정하는 것은 후보 설계이며, 실제 데이터 흐름은 도메인
요구사항·원자성 경계·장애 시나리오를 분석해 확정한다. MySQL Replica는
Primary에서 복제받는 것이지 Worker가 직접 쓰는 DB가 아니다.

### 핵심 실행 원칙

-   물리 Linux 서버 1대에 API와 Worker 컨테이너를 함께 실행할 수 있다.
-   컨테이너마다 별도의 JVM 프로세스가 존재하며, 호스트의
    커널·CPU·메모리는 공유한다.
-   API와 Worker는 별도 Spring Boot 실행 진입점과 빌드 산출물을 갖도록
    구성하는 방안을 우선 검토한다.
-   같은 Git 저장소의 Gradle 멀티모듈을 기본 방향으로 삼되, 현재 구조를
    분석해 최소 변경한다.
-   컨테이너 분리는 **프로세스 격리**이지 물리 서버 장애 격리가 아니다.
-   API 복수 인스턴스의 무중단성은 로드밸런서와 배포 방식, 상태 관리에
    따라 달라진다.
-   Worker 복수 인스턴스는 중복 처리 가능성을 전제로 설계한다.

## 3. 프로젝트 구조 제안

실제 저장소 구조를 우선 확인하고, 필요한 부분만 추가한다.

``` text
redis-event-platform/
├── apps/
│   ├── event-api/                # HTTP API 실행 애플리케이션
│   └── event-worker/             # 신규 후보: Stream 소비/비동기 처리
├── modules/                      # 기존 도메인 모듈 최대한 유지
├── infrastructure/
│   ├── docker/
│   ├── nginx/
│   ├── mysql/
│   ├── monitoring/
│   └── ansible/                   # 후순위: 서버 구성 자동화
├── experiments/
│   ├── concurrency/
│   ├── performance/
│   └── failure-recovery/
├── docs/
│   ├── adr/
│   ├── architecture/
│   ├── roadmap/
│   └── runbooks/
├── compose.yml
└── settings.gradle
```

**공통 모듈 원칙:** `common`에 모든 Entity/Repository/Service를 모으지
않는다. 재사용이 명확한 계약과 최소 공통 코드만 공유한다. API와 Worker의
DB 테이블 소유권, 스키마 마이그레이션 책임을 문서화한다.

## 4. 기능·정합성 설계 요구사항

### 4.1 이벤트·선착순 발급

-   요청 식별자와 사용자/이벤트 식별자를 정의한다.
-   Redis Lua 스크립트 등으로 재고 검사·예약·중복 요청 검사를 원자적으로
    처리하는 설계를 검토한다.
-   **Redis 재고 예약 성공과 Stream 이벤트 기록 사이의 원자성**을 반드시
    분석한다. 중간 실패로 예약만 되고 이벤트가 누락되는 경우를
    방지하거나 복구해야 한다.
-   발급 상태를 `PENDING / CONFIRMED / FAILED` 등으로 명확히 모델링하되
    기존 도메인 명칭을 우선한다. 현재는 도메인 코드가 없으므로 상태 명칭은 구현 전에 확정한다.
-   Redis는 빠른 제어·임시 상태에 활용하고, 최종 발급 기록의 진실
    공급원(source of truth)을 명시한다.

### 4.2 Worker / Redis Streams

-   Consumer Group 기반으로 여러 Worker가 작업을 분담한다.
-   성공적으로 **DB 커밋이 확인된 뒤** `XACK`한다.
-   DB 커밋 성공 직후 Worker가 죽어 ACK하지 못한 경우를 재현한다.
-   Pending Entries List, `XAUTOCLAIM` 또는 적합한 회수 전략으로 미완료
    메시지를 재처리한다.
-   재시도 횟수, 백오프, 장기 실패 메시지 처리 및 운영 알림을 설계한다.
-   Redis Streams 자체가 exactly-once를 보장한다고 설명하지 않는다.
    **at-least-once 전달 + DB 멱등 처리**를 기본으로 한다.

### 4.3 MySQL 8 정합성

-   발급 중복을 방지하는 **UNIQUE 제약**을 DB 최종 방어선으로 둔다.
-   MySQL 트랜잭션 경계, 격리 수준, 데드락/락 타임아웃 재시도 정책을
    정의한다.
-   JPA/JDBC 선택은 데이터 접근 패턴에 맞춰 결정한다.
-   Flyway 또는 기존 마이그레이션 도구로 스키마 변경을 버전 관리한다.
-   `utf8mb4`, 타임존, 커넥션 풀 크기, 인덱스, 실행 계획을 점검한다.
-   `INSERT ... ON DUPLICATE KEY UPDATE`를 사용한다면 기존 데이터가
    의도치 않게 갱신되지 않도록 의미를 검증한다.

## 5. Docker / Linux 요구사항

### Docker Compose

-   최소 `api`, `worker`, `redis`, `mysql` 서비스를 실행할 수 있어야
    한다.
-   확장 실험에서는 API 2개, Worker 2개, Nginx를 실행한다.
-   각 애플리케이션의 Docker 이미지/실행 명령/환경변수를 분리한다.
-   Docker Healthcheck와 Spring Boot Actuator readiness/liveness를
    적절히 구분한다.
-   Docker 네트워크, 포트 노출, 영속 볼륨, 재시작 정책을 명시한다.
-   컨테이너별 CPU·메모리 제한을 실험한다. JVM 힙과 컨테이너 메모리
    제한을 함께 고려한다.
-   `depends_on`만으로 DB 준비 완료를 보장하지 않는다. 실제 연결
    재시도와 헬스체크를 구성한다.
-   비밀번호·API 키는 Git에 커밋하지 않는다. `.env.example`만 제공하고
    비밀값은 별도 관리한다.
-   **프로덕션 MySQL 볼륨을 `docker compose down -v`로 삭제하지 않도록**
    문서에 경고한다.

### Linux 실습

-   Rocky Linux VM에 배포하는 과정을 문서화한다.
-   `ps`, `top`, `free`, `ss`, `lsof`, `ip`, `journalctl`, `systemctl`,
    `docker stats`, `docker inspect`를 활용한다.
-   JVM PID, 네트워크 포트, 컨테이너 프로세스, CPU·메모리 사용량을
    관찰한다.
-   Docker bridge, 포트 매핑, 호스트 네트워크, 방화벽의 차이를 기록한다.
-   SSH 접근, 최소 권한, 방화벽, 로그 보존과 재부팅 후 복구를 점검한다.

## 6. MySQL Primary--Replica 실습

-   **MySQL 8**을 사용하고 버전은 재현 가능하게 고정한다.
-   로컬 실습용 Primary/Replica 복제 구성과 GTID 기반 복제 방식을
    문서화한다.
-   Binary Log, Relay Log, Replication Lag, 읽기 일관성을 관찰한다.
-   Primary 장애 후 **수동 Failover 절차**를 실습한다.
-   두 DB 노드만으로 안전한 자동 Failover가 성립한다고 가정하지 않는다.
    Split-Brain, fencing, quorum/witness 필요성을 별도 문서화한다.
-   비동기 복제의 데이터 유실 가능성을 명시한다.
-   Replica는 백업을 대체하지 않는다. 논리/물리 백업 중 적합한 방식을
    선택하고 **복원 검증**을 포함한다.
-   가능하면 Binlog 기반 PITR(Point-in-Time Recovery) 실습을 추가한다.
-   API/Worker가 DB 전환 후 연결을 재수립하는 방법을 검증한다.

## 7. CI/CD 요구사항

1.  PR에서 Gradle 빌드, 단위 테스트, 통합 테스트를 실행한다.
2.  Docker 이미지를 빌드하고 버전/커밋 SHA 기반으로 태깅한다.
3.  Registry에 게시한다.
4.  API와 Worker를 **독립적으로 배포**할 수 있도록 한다.
5.  배포 후 헬스체크와 실패 시 롤백 절차를 제공한다.
6.  DB 마이그레이션은 호환성·적용 순서를 검토한다.
7.  운영 서버에 self-hosted runner를 직접 설치하는 방식의 보안 위험을
    검토한다.
8.  실서비스 접근 정보가 없으면 로컬/테스트 환경에서 재현 가능한 배포
    스크립트까지만 구현한다.

## 8. 관측성 요구사항

### 필수 메트릭

-   API: RPS/TPS, p50/p95/p99, 오류율, 활성 요청
-   JVM/호스트: CPU, RSS/Heap, GC, 스레드, 파일 디스크립터
-   Redis: 명령 지연, 메모리, evictions, connected clients, Stream
    lag/pending
-   Worker: 처리량, 성공/실패/재시도, 메시지 처리 지연, 미처리 메시지 수
-   MySQL: 커넥션 풀, 느린 쿼리, 트랜잭션 오류, 복제 지연
-   비즈니스: 예약 수, 확정 수, 실패 수, 중복 확정 수, 재고 불일치 수

### 운영 산출물

-   Spring Boot Actuator + Micrometer
-   Prometheus 수집 설정
-   Grafana 대시보드
-   구조화된 로그와 요청/이벤트 추적 ID
-   임계값 및 알림 예시
-   `docs/runbooks/`에 장애별 탐지·조치·복구 절차

## 9. 실험 계획과 성공 기준

  -----------------------------------------------------------------------
  실험                    절차                    필수 검증
  ----------------------- ----------------------- -----------------------
  API 확장                동일 부하로 API 1개/2개 TPS, p95/p99, 오류율,
                          비교                    환경 조건

  Worker 확장             Worker 1개/2개 비교     처리량, 중복 확정 0건

  ACK 이전 장애           DB 커밋 후 ACK 전       재전달되어도 중복 확정
                          Worker 종료             0건

  예약/발행 경계 장애     Redis 예약과 이벤트     누락 방지 또는 복구
                          기록 사이 장애 주입     가능

  Redis 장애              Redis 중지/재시작       데이터 손실 조건·복구
                                                  과정 명시

  MySQL 장애              Primary 중지 및 수동    RTO/RPO 측정, 데이터
                          승격                    유실 가능성 기록

  자원 경쟁               Worker CPU 부하 및 제한 API p99 영향 비교
                          변경                    

  재배포                  API만, Worker만 각각    독립 재시작·배포 확인
                          배포                    
  -----------------------------------------------------------------------

**측정 규칙:** CPU/RAM, 부하 발생기, 데이터 크기, 동시 사용자, 워밍업,
테스트 시간, 반복 횟수를 함께 기록한다. 측정 전 목표값을 임의의 '성공
수치'로 정하지 않는다.

## 10. 단계별 구현 로드맵

### Phase 0 --- 저장소 분석과 설계 (최우선)

-   [ ] 현재 모듈/패키지/DB/Redis/Compose/CI 상태 조사
-   [ ] PostgreSQL → MySQL 8 전환 영향 분석
-   [ ] 데이터 흐름·정합성·장애 지점 도식화
-   [ ] 기존 테스트 실행 결과 기록
-   [ ] 변경 계획과 ADR 초안 작성

**완료 기준:** 변경 범위, 의존성, 리스크, 단계별 PR 계획을 설명할 수
있다.

### Phase 1 --- MySQL 전환 및 단일 인스턴스 MVP

-   [ ] MySQL 8 연결, 설정, 마이그레이션 구성
-   [ ] 도메인 API 최소 구현 및 기존 연결 테스트의 MySQL 전환
-   [ ] Redis 기반 선착순 발급 최소 기능 구현/검증
-   [ ] 단일 API + Redis + MySQL Compose 구동
-   [ ] README 실행 방법 갱신

**완료 기준:** 클린 환경에서 Compose로 실행하고 테스트가 통과한다.

### Phase 2 --- API / Worker 분리

-   [ ] `event-worker` 애플리케이션 추가(현재 구조와 충돌하지 않도록)
-   [ ] Redis Streams 발행/소비와 Consumer Group
-   [ ] DB 커밋 후 ACK, 재처리, 멱등성
-   [ ] API/Worker 이미지 및 컨테이너 분리
-   [ ] 각각 독립 재시작 검증

**완료 기준:** Worker 중단/재시작 시 API가 살아 있고 발급 중복이 없다.

### Phase 3 --- API 수평 확장과 네트워크

-   [ ] API 2개 실행
-   [ ] Nginx Reverse Proxy/Load Balancing
-   [ ] 인스턴스 종료 시 트래픽 동작 확인
-   [ ] Linux PID/포트/네트워크 관찰 문서화

**완료 기준:** API 1개/2개 비교 실험이 재현 가능하다.

### Phase 4 --- 분산 Worker와 장애 복구

-   [ ] Worker 2개 실행
-   [ ] Pending 메시지 회수 및 중복 방어
-   [ ] 장애 주입 테스트 자동화
-   [ ] 처리량·지연·정합성 비교

**완료 기준:** 장애 후 재처리와 중복 방지 결과가 테스트로 입증된다.

### Phase 5 --- MySQL 복제/백업

-   [ ] Primary--Replica + GTID 복제
-   [ ] Replication Lag 관찰
-   [ ] 백업/복원 검증
-   [ ] 수동 Failover 및 RTO/RPO 기록

**완료 기준:** DB 장애 대응 Runbook이 재현 가능하다.

### Phase 6 --- CI/CD와 모니터링

-   [ ] GitHub Actions 빌드·테스트·이미지 생성
-   [ ] API/Worker 독립 배포
-   [ ] Prometheus/Grafana 대시보드
-   [ ] 주요 장애 알림
-   [ ] Rocky Linux 배포 및 운영 문서

**완료 기준:** 배포·관측·복구 절차가 문서와 스크립트로 재현 가능하다.

### Phase 7 --- 최종 성능·장애 분석

-   [ ] k6 성능 비교
-   [ ] p95/p99와 병목 분석
-   [ ] Redis/DB/Worker 장애 실험 결과 정리
-   [ ] 설계 트레이드오프 및 개선 방향 작성

**완료 기준:** 숫자와 로그로 설계 결정을 설명하는 포트폴리오 보고서
완성.

