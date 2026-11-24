# DB 기반 쿠폰 API 실행

MySQL·Redis가 실행 중이고 앱 연결 환경 변수가 설정된 Mac에서 실행한다. VM DB를 사용하는 경우 기존 SSH 터널을 유지한다.

```bash
./gradlew :apps:event-api:bootRun
```

앱 시작 시 Flyway V1/V2가 적용된다. 아래 예시는 로컬 8080 포트 기준이다.

## 이벤트 생성

```bash
curl -i -X POST http://localhost:8080/api/events \
  -H 'Content-Type: application/json' \
  -d '{"name":"쿠폰 실습","quantity":10,"startsAt":"2020-01-01T00:00:00Z","endsAt":"2099-01-01T00:00:00Z"}'
```

201 응답의 id와 Location을 확인한다. 아래의 1은 실제 이벤트 ID로 바꾼다.

## 이벤트 조회·발급·결과 조회

```bash
curl -i http://localhost:8080/api/events/1
curl -i -X POST http://localhost:8080/api/events/1/issuances \
  -H 'Content-Type: application/json' -d '{"userId":101}'
curl -i http://localhost:8080/api/events/1/issuances/101
```

발급 성공은 201이며 id, eventId, userId, confirmedAt을 반환한다. 이벤트 조회의 remainingQuantity가 1 감소한다. 동일 사용자로 다시 발급하면 409 ALREADY_ISSUED이며 재고는 줄지 않는다. 회원·인증 기능은 없으므로 userId는 테스트 식별자로 전달한다.

| 응답 | code | 의미 |
|---|---|---|
| 400 | INVALID_REQUEST | 이름·수량·기간·ID·본문 오류 |
| 404 | EVENT_NOT_FOUND / ISSUANCE_NOT_FOUND | 이벤트·발급 결과 없음 |
| 409 | ALREADY_ISSUED | 이미 확정된 사용자 |
| 409 | NOT_STARTED / ENDED | 발급 기간 밖 |
| 409 | SOLD_OUT | 재고 소진 |
| 503 | RETRY_LATER | 일시적인 잠금 충돌, Retry-After 확인 |

에러 본문은 code와 message로 구성한다. 발급 응답을 받지 못했다면 결과 조회로 확정 여부부터 확인한다. 이 API는 동기 DB 발급이며 Redis 대기열·Lua·Streams는 아직 사용하지 않는다.

## 검증

```bash
./gradlew build :apps:event-api:integrationTest --no-daemon
```

통합 테스트는 임시 MySQL·Redis를 사용하므로 VM DB에 테스트 데이터를 넣지 않는다. 동시성 테스트는 정확성 검증이며 부하 성능 측정이 아니다.
