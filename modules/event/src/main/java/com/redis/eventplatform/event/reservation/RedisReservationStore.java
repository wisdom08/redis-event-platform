package com.redis.eventplatform.event.reservation;

import com.redis.eventplatform.event.Event;
import com.redis.eventplatform.event.EventException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Component;

import static com.redis.eventplatform.event.EventException.Code.*;

/** 실험용 내부 컴포넌트. 동일 이벤트의 DB 직접 발급과 병용하면 안 된다. */
@Component
public class RedisReservationStore {
    private static final DateTimeFormatter TIME = DateTimeFormatter
            .ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSSSS'Z'").withZone(ZoneOffset.UTC);
    private static final DefaultRedisScript<String> INITIALIZE = script("initialize.lua");
    private static final DefaultRedisScript<String> RESERVE = script("reserve.lua");
    private final StringRedisTemplate redis;
    private final Clock clock;

    public RedisReservationStore(StringRedisTemplate redis, Clock clock) {
        this.redis = redis;
        this.clock = clock;
    }

    /** 발급 전 이벤트만 준비한다. 같은 설정의 재호출은 재고를 초기화하지 않는다. */
    public boolean initialize(Event event) {
        requirePositive(event.id());
        if (event.totalQuantity() <= 0 || event.remainingQuantity() != event.totalQuantity()
                || event.startsAt() == null || event.endsAt() == null) {
            throw new EventException(INVALID_REQUEST, "발급 전의 유효한 이벤트만 초기화할 수 있습니다.");
        }
        String startsAt = timestamp(event.startsAt());
        String endsAt = timestamp(event.endsAt());
        if (startsAt.compareTo(endsAt) >= 0) {
            throw new EventException(INVALID_REQUEST, "시작 시각은 종료 시각보다 빨라야 합니다.");
        }
        String result = redis.execute(INITIALIZE, keys(event.id()),
                Integer.toString(event.totalQuantity()), startsAt, endsAt);
        return switch (requireResult(result)) {
            case "CREATED" -> true;
            case "EXISTS" -> false;
            default -> throw new IllegalStateException("Redis 예약 초기화 실패: " + result);
        };
    }

    public Reservation reserve(long eventId, long userId) {
        requirePositive(eventId);
        requirePositive(userId);
        String result = requireResult(redis.execute(RESERVE, keys(eventId),
                Long.toString(eventId), Long.toString(userId), timestamp(clock.instant())));
        if (result.startsWith("CREATED:") || result.startsWith("REPLAY:")) {
            int separator = result.indexOf(':');
            return new Reservation(eventId, userId, result.substring(separator + 1),
                    result.startsWith("REPLAY:"));
        }
        throw switch (result) {
            case "NOT_STARTED" -> new EventException(NOT_STARTED, "예약 시작 전입니다.");
            case "ENDED" -> new EventException(ENDED, "예약 기간이 종료되었습니다.");
            case "SOLD_OUT" -> new EventException(SOLD_OUT, "예약 가능한 쿠폰이 없습니다.");
            // DB 존재 여부와 Redis 준비 상태를 혼동하지 않는다. 자동 재초기화하지 않는다.
            default -> new IllegalStateException("Redis 예약 상태 확인 필요: " + result);
        };
    }

    private static List<String> keys(long eventId) {
        String prefix = "coupon:reservation:{" + eventId + "}:";
        return List.of(prefix + "state", prefix + "stream");
    }

    private static DefaultRedisScript<String> script(String name) {
        DefaultRedisScript<String> script = new DefaultRedisScript<>();
        script.setLocation(new ClassPathResource("redis/reservation/" + name));
        script.setResultType(String.class);
        return script;
    }

    private static String timestamp(Instant instant) {
        if (instant.isBefore(Instant.parse("1000-01-01T00:00:00Z"))
                || !instant.isBefore(Instant.parse("+10000-01-01T00:00:00Z"))) {
            throw new EventException(INVALID_REQUEST, "시각은 UTC 기준 1000~9999년 범위여야 합니다.");
        }
        return TIME.format(instant);
    }

    private static String requireResult(String result) {
        if (result == null) throw new IllegalStateException("Redis 스크립트 응답이 없습니다.");
        return result;
    }

    private static void requirePositive(long id) {
        if (id <= 0) throw new EventException(INVALID_REQUEST, "식별자는 양수여야 합니다.");
    }
}
