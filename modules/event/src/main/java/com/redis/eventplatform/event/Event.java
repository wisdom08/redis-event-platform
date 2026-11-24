package com.redis.eventplatform.event;

import java.time.Instant;

public record Event(long id, String name, int totalQuantity, int remainingQuantity,
                    Instant startsAt, Instant endsAt) {}
