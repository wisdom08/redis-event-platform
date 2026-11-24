package com.redis.eventplatform.event;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import static com.redis.eventplatform.event.EventException.Code.INVALID_REQUEST;

public record CreateEvent(String name, Integer quantity, Instant startsAt, Instant endsAt) {
    public CreateEvent {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw new EventException(INVALID_REQUEST, "이벤트 이름은 1~200자여야 합니다.");
        }
        if (quantity == null || quantity <= 0) {
            throw new EventException(INVALID_REQUEST, "수량은 양수여야 합니다.");
        }
        if (startsAt == null || endsAt == null) {
            throw new EventException(INVALID_REQUEST, "시작·종료 시각이 필요합니다.");
        }
        Instant earliest = Instant.parse("1000-01-01T00:00:00Z");
        Instant latest = Instant.parse("+10000-01-01T00:00:00Z");
        if (startsAt.isBefore(earliest) || !startsAt.isBefore(latest)
                || endsAt.isBefore(earliest) || !endsAt.isBefore(latest)) {
            throw new EventException(INVALID_REQUEST, "시각은 UTC 기준 1000~9999년 범위여야 합니다.");
        }
        startsAt = startsAt.truncatedTo(ChronoUnit.MICROS);
        endsAt = endsAt.truncatedTo(ChronoUnit.MICROS);
        if (!startsAt.isBefore(endsAt)) {
            throw new EventException(INVALID_REQUEST, "시작 시각은 종료 시각보다 빨라야 합니다.");
        }
    }
}
