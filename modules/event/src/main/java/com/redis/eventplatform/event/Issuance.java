package com.redis.eventplatform.event;

import java.time.Instant;

public record Issuance(long id, long eventId, long userId, Instant confirmedAt) {}
