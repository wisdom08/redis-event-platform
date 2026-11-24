# 현재 구현과 목표의 차이

- 최초 확인일: 2026-11-12 (아래 표는 전환 전 분석 이력)
- 범위: 코드·설정 정적 확인 및 문서 갱신. 이번 작업에서 빌드·통합 테스트·장애 실험은 실행하지 않았다.
- 기준: [공유 로드맵](infrastructure-reliability-roadmap.md), [ADR 0002](../adr/0002-infrastructure-reliability-direction.md)

## 확인한 현재 상태

| 영역 | 근거 | 현재 상태와 차이 |
|---|---|---|
| 실행 구조 | `settings.gradle`, `apps/event-api/build.gradle` | API 1개와 기능 모듈 5개. Worker 앱·별도 이미지 없음 |
| 버전 | `build.gradle`, `gradle/wrapper/gradle-wrapper.properties` | Java 25, Spring Boot 4.0.9, Gradle 9.8.2 |
| DB | API build.gradle, `application.yml`, `compose.yml` | PostgreSQL 드라이버·JDBC URL·17.6-alpine 이미지 사용. MySQL 미구현 |
| 도메인 | `modules/*/src/main/java/` | package-info 골격만 존재. 도메인 API·Entity·Repository·테이블·마이그레이션 없음 |
| Redis | `compose.yml`, `InfrastructureIntegrationTest.java` | 8.2.11-alpine 고정. 연결 테스트만 있으며 Lua·ZSET·Streams 미구현 |
| 테스트 | API build.gradle, `InfrastructureIntegrationTest.java` | integration 태그와 별도 태스크. PostgreSQL SELECT 1·Redis 저장/조회 검증 |
| CI | `.github/workflows/ci.yml` | Temurin 25로 build와 integrationTest 실행 설정. 이미지·배포 파이프라인 없음 |
| 관측·운영 | `application.yml`, `infrastructure/` | Actuator·Prometheus endpoint, DB/Redis readiness 설정. 대시보드·배포·복제·복구 구성 없음 |

사용자 확인상 PostgreSQL을 별도로 구성한 환경과 보존할 데이터는 없다. 위 PostgreSQL 항목은 저장소에 존재하는 연결 설정·테스트 코드를 뜻하며, 실제 DB 운영이나 기동 완료를 의미하지 않는다.

현재 구현된 도메인 API가 없으므로 로드맵의 “기존 도메인 API 정상화”는 신규 MVP 구현과 기존 연결 테스트의 MySQL 전환으로 해석한다. 기존 Phase 번호는 폐기하고 새 Phase 0~7을 따른다. 로컬 output의 과거 환경 구성 Phase 0은 새 Phase 0 완료 근거로 사용하지 않는다.

## 데이터 흐름과 장애 경계

현재 연결 흐름은 `event-api → PostgreSQL / Redis`이며 발급 처리는 없다. 후보 흐름은 `API → Redis 예약·Stream 기록 → Worker → MySQL 확정 → ACK`이다.

- 예약만 남고 이벤트가 누락되는 경계: Lua 실행 범위와 실패·복구 전략 정의 필요.
- Redis 처리와 DB 확정 사이: 단일 트랜잭션이 아니므로 누락·중복·재고 불일치 대조와 복구 필요.
- DB 커밋 후 ACK 전 종료: 재전달과 UNIQUE 제약·멱등 처리 검증 필요.
- DB 커밋 후 알림 전 종료: 알림 유실·재전송 정책 정의 필요.
- MySQL 비동기 복제 후 승격: 복제 지연에 따른 유실·RTO/RPO 측정 필요.

## 전환 영향과 단계별 PR 계획

1. Phase 0 문서: 현황·영향·ADR·수용 기준 정리. 기존 테스트 실행 결과와 설계 미확정 사항을 보완한 뒤 구현 범위를 확정한다.
2. Phase 1 DB 기반: PostgreSQL 드라이버·URL·환경변수 예시·Compose·Testcontainers를 MySQL 8로 함께 전환한다. 현재 저장소에 도메인 스키마는 없고, 사용자 확인상 별도로 구성한 PostgreSQL 환경과 보존할 데이터도 없다. 데이터 이관 없이 설정을 교체하며 볼륨 삭제는 필요하지 않다. 이미지 버전, 마이그레이션 도구, 문자셋·타임존을 확정한다.
3. Phase 1 MVP: 테이블·유니크 제약·마이그레이션과 발급 API를 추가하고 초과 발급·중복 확정·실패를 검증한다. 재고 예약과 이벤트 기록 방식, DB 격리 수준·재시도 정책을 문서화한다.
4. Phase 2: Worker 진입점·산출물·이미지, Streams, ACK·재처리와 독립 재시작을 구현한다. 공유 계약·테이블 소유권·마이그레이션 책임을 확정한다.
5. Phase 3~4: Nginx/API 확장 후 Worker 확장·Pending 회수·장애 주입을 각각 검증한다.
6. Phase 5: MySQL GTID 복제·백업 복원·수동 승격을 독립 실험과 Runbook으로 추가한다.
7. Phase 6~7: 이미지 생성·독립 배포·롤백·관측·Rocky Linux 실습을 구성하고 성능·장애 보고서를 작성한다.

## 검증 현황과 다음 명령

이번 변경은 문서만 대상으로 하며 애플리케이션 동작·컨테이너 기동 성공을 확인한 작업이 아니다. Phase 0의 기존 테스트 결과는 아직 미확인이다. 다음 명령을 실행하고 실제 결과를 기록해야 한다.

```bash
docker compose config --quiet
./gradlew build :apps:event-api:integrationTest --no-daemon
```

MVP 이후에는 동일 이벤트·사용자 중복 확정 0건과 재고 초과 발급 0건, 예약/발행 경계 장애, DB 커밋 후 ACK 전 장애를 테스트한다. API/Worker 1개·2개 비교, 자원 경쟁, 복제·복원은 각 단계의 측정 환경과 반복 조건을 함께 기록한다. 현재 문서 갱신만으로 Phase 0 전체 또는 이후 단계를 완료로 표시하지 않는다.

## 2026-11-15 MySQL 기반 설정 적용

- MySQL 8.4.13 드라이버·JDBC URL·Compose·Testcontainers 설정으로 변경했다.
- 서버 utf8mb4·UTC 설정, 루트/앱 계정 분리, 일반 계정 SELECT 1 healthcheck를 구성했다. MySQL·Redis 포트는 localhost에만 바인딩한다.
- Rocky Linux 10.2 aarch64 VM은 사용자가 OS·SSH 키·Docker 설치 완료를 알려준 상태다. 실제 VM의 서비스 기동은 아직 확인하지 않았다.
- 데이터 이관이나 기존 볼륨 삭제는 수행하지 않았다. 테이블·마이그레이션·쿠폰 MVP·Worker는 후속 작업이다.
- 검증: `docker compose config --quiet`, `./gradlew build :apps:event-api:integrationTest --no-daemon`, `git diff --check`, 로컬 Markdown 링크 검사 통과. 통합 테스트 1개에서 MySQL 연결·버전·문자셋·UTC 설정과 Redis 저장/조회를 확인했다. Docker Desktop 28.1.1 및 로컬 OpenJDK 25.0.4에서 실행했으며 Temurin CI와 Rocky VM의 실제 Compose 기동은 별도 확인이 필요하다.

## Flyway와 초기 스키마 추가

Flyway 실행 책임은 event-api에 두고 V1 events, V2 coupon_issuances를 추가했다. 이벤트 수량·기간 CHECK와 발급 (event_id, user_id) UNIQUE·외래키를 적용했다. 발급 API·재고 차감 트랜잭션·Redis 예약은 아직 구현하지 않았으므로 초과 발급 방지가 완성된 것은 아니다. 세부 결정은 ADR 0003을 따른다.

검증: `./gradlew build :apps:event-api:integrationTest --no-daemon` 통과(통합 테스트 4개). 빈 MySQL에서 V1/V2 적용·재실행 0건·체크섬 검증, 동일 이벤트/사용자 중복 거부, 다른 이벤트/사용자 발급 허용, 재고·기간 CHECK 및 외래키 거부를 확인했다. `git diff --check`와 로컬 문서 링크 검사도 통과했다. VM DB에는 이번 작업에서 접속하지 않았다.

후속 결정: 실제 DB에 V1/V2 미적용임을 사용자에게 확인하고 초기 SQL의 수량·기간·사용자 ID CHECK를 제거했다. DB에는 PK·NOT NULL·UNIQUE·FK를 유지한다. 수량·기간·사용자 ID 검증과 재고 차감 트랜잭션은 후속 애플리케이션 구현 대상이다. 위 CHECK 테스트 결과는 제거 전 이력이다.

CHECK 제거 후 재검증: build 및 integrationTest 통과(5개). Flyway 적용·재실행, 중복 발급 거부, 존재하지 않는 이벤트 참조 거부, 발급 이력이 있는 이벤트 삭제 거부와 데이터 보존을 확인했다.

## DB 기반 쿠폰 API 구현

modules/event에 JDBC 기반 생성·조회·발급 서비스와 저장소, 앱에 HTTP 계층을 추가했다. 수량·기간·식별자 검증은 앱에서 수행하고 READ COMMITTED 트랜잭션에서 이벤트 행 잠금 → 중복/기간 검사 → 조건부 재고 차감 → 발급 INSERT 순서로 처리한다. Redis Lua·Streams·Worker는 미구현이다.

검증: build + integrationTest 통과 후 잠금 실패 응답 테스트를 추가해 integrationTest 재실행도 통과했다. 단위 4개·통합 12개. 재고 10개에 서로 다른 사용자 40건은 10건 확정·30건 품절, 같은 사용자 40건은 1건 확정·39건 중복으로 검증했다. 저장 실패 시 재고 롤백, 시작 포함/종료 제외, HTTP 201/400/404/409 및 잠금 예외 주입 시 503·Retry-After를 확인했다. 잠금 실패 테스트는 예외 주입이며 실제 데드락 재현 실험은 아니다. 성능 측정·실제 VM DB 변경·배포는 수행하지 않았다.

발급 정책과 보장 범위는 ADR 0004, 호출 예시는 docs/runbooks/coupon-api.md를 따른다. Phase 1 전체 완료는 아니며 Redis Lua 비교 및 실행 구성·실험은 남아 있다.
