# ADR 0001: 초기 프로젝트 구조

- 상태: Accepted
- Java 25, Spring Boot 4.0.9, Gradle 9.8.2를 사용한다.
- 실행 앱은 apps/event-api 하나다. 5개 기능 모듈은 java-library이며 앱만 각 모듈에 의존한다.
- 초기에는 모듈 간 의존성을 두지 않는다. 공통 모듈과 별도 worker는 실제 필요가 생길 때 추가한다.
- 각 모듈은 com.redis.eventplatform 하위 패키지를 사용해 앱의 컴포넌트/엔티티 스캔 범위에 포함한다.
- 스키마 자동 생성은 사용하지 않는다. 첫 테이블 추가 시 버전 관리된 마이그레이션을 도입한다.
- 통합 테스트는 Docker를 필수로 요구하며 명시적인 integrationTest 태스크로 실행한다. CI에서 항상 실행한다.
- Redis와 PostgreSQL 이미지 버전은 Compose와 Testcontainers에서 함께 변경한다.
- 운영 배포/보안/복제 설정과 도메인 기능은 후속 단계에서 구현한다.
