package com.redis.eventplatform.event;

import java.time.Instant;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class CreateEventTest {
    private static final Instant START = Instant.parse("2030-01-01T00:00:00Z");

    @Test
    void rejectsInvalidNameQuantityAndPeriod() {
        assertThrows(EventException.class, () -> new CreateEvent(null, 1, START, START.plusSeconds(1)));
        assertThrows(EventException.class, () -> new CreateEvent(" ", 1, START, START.plusSeconds(1)));
        assertThrows(EventException.class, () -> new CreateEvent("x".repeat(201), 1, START, START.plusSeconds(1)));
        assertThrows(EventException.class, () -> new CreateEvent("test", null, START, START.plusSeconds(1)));
        assertThrows(EventException.class, () -> new CreateEvent("test", 0, START, START.plusSeconds(1)));
        assertThrows(EventException.class, () -> new CreateEvent("test", -1, START, START.plusSeconds(1)));
        assertThrows(EventException.class, () -> new CreateEvent("test", 1, null, START));
        assertThrows(EventException.class, () -> new CreateEvent("test", 1, START, null));
        assertThrows(EventException.class, () -> new CreateEvent("test", 1, START, START));
        assertThrows(EventException.class, () -> new CreateEvent("test", 1, START, START.minusSeconds(1)));
    }

    @Test
    void rejectsPeriodThatCollapsesAtDatabasePrecision() {
        assertThrows(EventException.class, () -> new CreateEvent("test", 1, START, START.plusNanos(1)));
    }

    @Test
    void rejectsTimeOutsideMysqlDatetimeRange() {
        assertThrows(EventException.class, () -> new CreateEvent("test", 1,
                Instant.parse("0999-01-01T00:00:00Z"), START));
        assertThrows(EventException.class, () -> new CreateEvent("test", 1,
                START, Instant.parse("+10000-01-01T00:00:00Z")));
    }

    @Test
    void acceptsValidEventAndNormalizesTimeToMicroseconds() {
        var event = new CreateEvent("test", 1, START.plusNanos(1234), START.plusSeconds(1));
        assertEquals(START.plusNanos(1000), event.startsAt());
    }
}
