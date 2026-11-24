package com.redis.eventplatform.event;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import static com.redis.eventplatform.event.EventException.Code.*;

@Service
public class EventService {
    private final EventRepository repository;
    private final Clock clock;

    public EventService(EventRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Transactional
    public Event create(CreateEvent command) {
        return get(repository.create(command));
    }

    @Transactional(readOnly = true)
    public Event get(long eventId) {
        requirePositive(eventId);
        return repository.find(eventId, false)
                .orElseThrow(() -> new EventException(EVENT_NOT_FOUND, "이벤트가 없습니다."));
    }

    @Transactional(readOnly = true)
    public Issuance getIssuance(long eventId, long userId) {
        get(eventId);
        requirePositive(userId);
        return repository.findIssuance(eventId, userId)
                .orElseThrow(() -> new EventException(ISSUANCE_NOT_FOUND, "발급 기록이 없습니다."));
    }

    @Transactional(isolation = Isolation.READ_COMMITTED)
    public Issuance issue(long eventId, long userId) {
        requirePositive(eventId);
        requirePositive(userId);
        Event event = repository.find(eventId, true)
                .orElseThrow(() -> new EventException(EVENT_NOT_FOUND, "이벤트가 없습니다."));
        if (repository.findIssuance(eventId, userId).isPresent()) {
            throw new EventException(ALREADY_ISSUED, "이미 발급받은 사용자입니다.");
        }
        var now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        if (now.isBefore(event.startsAt())) {
            throw new EventException(NOT_STARTED, "발급 시작 전입니다.");
        }
        if (!now.isBefore(event.endsAt())) {
            throw new EventException(ENDED, "발급 기간이 종료되었습니다.");
        }
        if (repository.decreaseStock(eventId) != 1) {
            throw new EventException(SOLD_OUT, "쿠폰이 소진되었습니다.");
        }
        return repository.insertIssuance(eventId, userId, now);
    }

    private static void requirePositive(long id) {
        if (id <= 0) throw new EventException(INVALID_REQUEST, "식별자는 양수여야 합니다.");
    }
}
