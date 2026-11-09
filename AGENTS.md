# Repository Guidelines

## 로컬 작업 기록
- 작업을 시작할 때 루트에 AGENTS.local.md가 있으면 읽고 이전 작업 맥락을 참고한다. 없으면 이 지침만으로 진행한다.
- 작업 종료 시 AGENTS.local.md에 현재 상태, 주요 결정, 검증 결과와 다음 할 일을 간결하게 갱신한다.
- 로컬 기록은 과거 맥락이므로 현재 코드와 사용자 지시를 기준으로 사실 여부를 확인한다.
- AGENTS.local.md는 개인 작업 기록이며 커밋하거나 비밀 정보를 기록하지 않는다. 공유할 결정은 docs/adr 또는 README.md에 반영한다.

## 프로젝트 목적과 현재 범위
- README.md와 docs/adr/0001-initial-project-structure.md를 먼저 확인한다.
- Redis 기반 쿠폰 발급·대기열의 동시성, 정합성, 처리량, 장애 복구를 검증하는 프로젝트다.
- 현재는 초기 골격이다. 도메인 API, 테이블, 워커와 운영 배포는 아직 구현하지 않았다.
- 기본 대화와 프로젝트 문서는 한국어로 작성하고 코드 식별자는 영어로 작성한다.

## 구조와 의존성
- apps/event-api: 단일 Spring Boot 실행 앱과 인프라 연결 설정.
- modules/event: 쿠폰 발급과 대기열.
- modules/traffic-control: 호출 제한.
- modules/messaging: 메시징과 알림.
- modules/consistency-cache: 멱등성과 조회 캐시.
- modules/leaderboard: 점수와 순위.
- 앱이 기능 모듈에 의존한다. 모듈에서 앱을 참조하거나 모듈 간 순환 의존성을 만들지 않는다.
- Java 패키지는 com.redis.eventplatform 하위를 사용한다.
- 별도 worker, 공통 모듈, 마이크로서비스는 실제 필요가 생길 때 도입하고 구조 결정은 docs/adr에 기록한다.

## 개발 환경과 명령
- Eclipse Temurin JDK 25를 기준으로 하며 Gradle Wrapper를 사용한다.
- 버전의 기준은 build.gradle, gradle/wrapper/gradle-wrapper.properties, compose.yml이다.
- 로컬 인프라: docker compose up -d --wait
- 앱 실행: ./gradlew :apps:event-api:bootRun
- 빌드 및 단위 테스트: ./gradlew build
- 인프라 통합 테스트: ./gradlew :apps:event-api:integrationTest
- CI와 동일한 검증: ./gradlew build :apps:event-api:integrationTest --no-daemon
- Compose 설정 검사: docker compose config --quiet
- 상태 확인: /actuator/health 및 /actuator/health/readiness
- .env는 Compose가 읽는다. 앱에는 셸 환경 변수나 IDE 실행 설정으로 별도 전달한다.

## 구현 및 테스트 원칙
- 기능 변경에는 핵심 동작과 실패 시나리오를 검증하는 테스트를 추가한다.
- 통합 테스트에는 @Tag("integration")을 붙인다. Testcontainers와 Docker를 사용하며 로컬 Compose 데이터에 의존하지 않는다.
- build만으로 통합 테스트가 실행되지는 않는다. DB/Redis 연결 및 설정 변경 시 integrationTest도 실행한다.
- Docker나 네트워크 제한으로 검증이 막히면 수행한 검사와 미검증 항목을 구분해 보고한다. 테스트를 우회하고 성공했다고 보고하지 않는다.
- DB 스키마 자동 생성에 의존하지 않는다. 첫 테이블 추가 시 버전 관리된 마이그레이션을 도입한다.
- Redis Lua의 원자성과 PostgreSQL 트랜잭션의 경계를 구분한다. Redis 키만으로 exactly-once를 보장한다고 가정하지 않는다.
- 쿠폰 기능에서는 초과 발급과 동일 이벤트·사용자 중복 확정을 검증한다. 대기 순서와 최종 발급 순서의 보장 범위를 명시한다.
- Streams 소비자는 중복 처리와 DB 커밋 이후 알림을 고려한다.
- Redis/PostgreSQL 이미지 버전 변경 시 Compose와 Testcontainers를 함께 갱신한다.

## 문서와 실험
- 실행 방법과 설정이 바뀌면 README.md도 갱신한다.
- 부하 스크립트는 load-tests, 실험 시나리오는 experiments, 결과는 docs/results에 둔다.
- 성능 수치는 측정 후 기록한다. 하드웨어, 버전, 데이터 크기, 동시성, 부하 패턴, 반복 횟수를 함께 남긴다.
- .env, 자격 증명, IDE 설정과 빌드 산출물은 커밋하지 않는다. 공유할 설정 예시는 .env.example에 작성한다.
