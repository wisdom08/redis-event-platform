# ADR 0002: MySQL 전환과 API/Worker 독립 실행 방향

- 상태: Accepted (목표 방향 승인, 구현 예정)
- 날짜: 2026-11-12
- 관련: [ADR 0001](0001-initial-project-structure.md), [공유 로드맵](../roadmap/infrastructure-reliability-roadmap.md)

## 배경

현재는 PostgreSQL·Redis 연결과 단일 Spring Boot 앱만 있는 초기 골격이다. 이벤트 플랫폼의 정합성을 유지하면서 독립 프로세스 운영, 분산 소비, DB 복제·백업과 장애 복구를 검증하도록 범위를 확장한다.

## 결정

- 목표 DB는 MySQL 8이다. Phase 0에서 영향 분석 후 Phase 1에서 드라이버, 설정, Compose, Testcontainers, CI 검증 및 실행 문서를 함께 변경한다. 정확한 이미지 버전은 구현 시 고정하고 Compose와 테스트에 동일하게 적용한다.
- 사용자 확인상 별도로 구성한 PostgreSQL 환경과 보존할 데이터는 없다. 데이터 이관 없이 저장소의 드라이버·연결 설정·Compose·테스트를 MySQL로 교체한다. 볼륨 삭제 작업은 전환의 전제 조건으로 두지 않는다.
- 첫 도메인 테이블부터 버전 관리된 마이그레이션을 사용한다. 도구 선택과 API/Worker의 테이블 소유권·마이그레이션 실행 책임은 스키마 도입 시 명시한다.
- Phase 2에서 `apps/event-worker`를 별도 Spring Boot 진입점·산출물·이미지로 추가한다. 기존 기능 모듈과 앱에서 모듈로 향하는 의존 방향을 유지한다. 포괄적인 common 모듈은 만들지 않는다.
- Streams 처리는 at-least-once 전달과 DB 멱등 처리를 기본으로 한다. DB 커밋 확인 후 ACK하고, 커밋 후 ACK 이전 종료와 Pending 회수를 검증한다. 동일 이벤트·사용자 UNIQUE 제약을 최종 중복 방어선으로 둔다.
- Redis 예약과 Stream 기록의 원자성, Redis–MySQL 사이 비원자적 경계, 최종 발급 기록의 진실 공급원과 보상·대조 절차는 MVP 설계에서 확정한다. Lua나 분산 락만으로 exactly-once를 주장하지 않는다.
- MySQL Primary–Replica는 비동기 복제와 수동 Failover 실습으로 시작한다. 복제 지연·유실 가능성, fencing과 split-brain을 문서화하고 백업 복원을 별도로 검증한다.
- GitHub Actions에서 기존 빌드·통합 테스트를 유지하고 이후 이미지 생성·독립 배포·헬스체크·롤백을 추가한다. Rocky Linux와 Prometheus/Grafana 운영 실습을 단계적으로 도입한다.

## 영향과 제한

MySQL의 격리 수준, 데드락·락 타임아웃 재시도, utf8mb4, 타임존, 풀 크기와 인덱스 검증이 필요하다. Worker 복수 실행은 중복 전달과 공유 자원 경쟁을 발생시키므로 처리량과 API p99, 재고·중복 확정을 함께 측정한다.

같은 호스트의 독립 컨테이너는 프로세스 격리이며 호스트 장애 격리가 아니다. 두 DB만으로 자동 무중단 전환을 보장하지 않는다. Kafka, Kubernetes와 광범위한 마이크로서비스 분할은 초기 범위에서 제외한다.

이 ADR은 방향 결정이며 코드 전환이나 성능·복구 검증 완료를 뜻하지 않는다. 현재 실행 방법은 PostgreSQL 기준 README를 따른다.
