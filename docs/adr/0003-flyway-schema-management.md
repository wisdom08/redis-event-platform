# ADR 0003: Flyway SQL 마이그레이션으로 스키마 관리

- 상태: Accepted
- 날짜: 2026-11-15

## 결정

MySQL 스키마는 Flyway의 버전별 SQL 파일로 관리한다. Spring Boot BOM이 관리하는 starter-flyway와 flyway-mysql을 사용한다. Liquibase나 Hibernate 자동 스키마 변경과 병행하지 않는다. JPA는 ddl-auto: validate를 유지하며 Entity가 추가되면 매핑을 검증한다.

현재 마이그레이션의 실행 책임은 event-api에 있다. 파일은 apps/event-api/src/main/resources/db/migration에 두고 앱 시작 시 적용한다. 이벤트 테이블의 도메인 소유권은 modules/event에 있다. Worker는 독자적인 스키마 이력을 만들지 않으며, Worker 분리·독립 배포 시 마이그레이션 실행을 배포 단계로 분리한다.

적용한 V 파일은 수정하지 않고 다음 버전을 추가한다. 기존 스키마를 자동 승인하는 baseline-on-migrate와 clean은 사용하지 않는다. 개발·테스트에 같은 SQL을 적용하며 Testcontainers의 빈 MySQL에서 시작과 재실행을 검증한다.

## 초기 테이블과 보장 범위

- events: 이벤트 이름·총수량·잔여수량·기간. 수량·기간 검증은 애플리케이션이 담당한다.
- coupon_issuances: 확정된 발급만 저장. event_id 외래키와 (event_id, user_id) UNIQUE 제약. ON DELETE CASCADE를 사용하지 않으므로 발급 이력이 있는 이벤트 삭제는 거부한다. 실패·대기 요청 상태는 아직 도입하지 않는다.
- user_id는 외부에서 전달할 양의 정수 식별자다. 회원 기능과 사용자 테이블은 현재 범위에 없다.
- 시간 컬럼은 DATETIME(6)이며 UTC로 기록·해석한다. 이벤트별 사용자 조회는 UNIQUE 인덱스를 사용한다.

DB에는 PK·NOT NULL·UNIQUE·FK를 유지하고 수량·기간·사용자 ID의 CHECK는 두지 않는다. 비즈니스 규칙을 애플리케이션에 모으기 위한 결정이다. 도메인 검증과 재고 조건부 차감·발급 INSERT의 DB 트랜잭션 처리는 [ADR 0004](0004-db-coupon-issuance.md)에 따라 구현한다. Redis 예약과 DB 확정 사이 정합성은 별도 설계다.

## 운영 영향

앱 시작 시 DB에 DDL이 실행되며 마이그레이션 실패 시 시작을 중단한다. 현재 개발 계정은 event_platform DB 권한을 가지고 있다. 배포 자동화 단계에서 마이그레이션 계정과 실행 계정을 분리한다.

MySQL DDL은 여러 변경 전체의 트랜잭션 롤백을 보장하지 않는다. 적용 실패 시 상태를 확인하고 원인을 해소한 뒤 복구한다. repair로 체크섬 오류를 무조건 덮거나 적용 파일을 수정하지 않는다. 앱 롤백과 DB 롤백은 구분하고, 호환 가능한 추가 변경부터 배포한다.

사용자 확인상 V1/V2는 실제 DB에 아직 적용하지 않았으므로 초기 SQL에서 CHECK를 제거했다. Testcontainers의 이전 임시 DB는 폐기되며, 공유 DB 적용 후에는 기존 파일을 수정하지 않는다.
