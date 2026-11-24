package com.redis.eventplatform.event.reservation;

/** Redis 접수 결과이며 MySQL 발급 확정을 의미하지 않는다. */
public record Reservation(long eventId, long userId, String messageId, boolean replayed) {}
