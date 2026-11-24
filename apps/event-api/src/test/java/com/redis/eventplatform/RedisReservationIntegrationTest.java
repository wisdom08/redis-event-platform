package com.redis.eventplatform;

import com.redis.eventplatform.event.Event;
import com.redis.eventplatform.event.EventException;
import com.redis.eventplatform.event.reservation.RedisReservationStore;
import com.redis.eventplatform.event.reservation.Reservation;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongFunction;
import org.junit.jupiter.api.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import static org.assertj.core.api.Assertions.*;
import static com.redis.eventplatform.event.EventException.Code.*;

@Tag("integration")
@Testcontainers
class RedisReservationIntegrationTest {
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(
            DockerImageName.parse("redis:8.2.11-alpine")).withExposedPorts(6379);
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00.123456Z");
    private static final AtomicLong IDS = new AtomicLong(1000);
    private static LettuceConnectionFactory connectionFactory;
    private static StringRedisTemplate redis;

    @BeforeAll
    static void connect() {
        connectionFactory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379)));
        connectionFactory.afterPropertiesSet();
        redis = new StringRedisTemplate(connectionFactory);
    }

    @AfterAll
    static void close() {
        if (connectionFactory != null) connectionFactory.destroy();
    }

    @Test
    void reservesStockAndRecordsWorkWithExactIdentifiers() {
        Event event = event(Long.MAX_VALUE, 2);
        store(NOW).initialize(event);
        Reservation result = store(NOW).reserve(event.id(), Long.MAX_VALUE);
        assertThat(result.replayed()).isFalse();
        assertThat(result.messageId()).matches("\\d+-\\d+");
        assertStock(event.id(), 1, 1);
        var messages = redis.opsForStream().range(stream(event.id()),
                org.springframework.data.domain.Range.unbounded());
        assertThat(messages).hasSize(1);
        assertThat(messages.getFirst().getValue())
                .containsEntry("eventId", Long.toString(Long.MAX_VALUE))
                .containsEntry("userId", Long.toString(Long.MAX_VALUE))
                .containsEntry("reservedAt", "2030-01-01T00:00:00.123456Z")
                .containsEntry("schemaVersion", "1");
    }

    @Test
    void retryReturnsSameMessageEvenAfterSoldOutAndEnd() {
        Event event = event(1);
        store(NOW).initialize(event);
        Reservation first = store(NOW).reserve(event.id(), 1);
        Reservation retry = store(event.endsAt()).reserve(event.id(), 1);
        assertThat(retry.replayed()).isTrue();
        assertThat(retry.messageId()).isEqualTo(first.messageId());
        assertStock(event.id(), 0, 1);
        assertCode(() -> store(NOW).reserve(event.id(), 2), SOLD_OUT);
    }

    @Test
    void initializationNeverRefillsStockAndRejectsChangedConfiguration() {
        Event event = event(2);
        assertThat(store(NOW).initialize(event)).isTrue();
        store(NOW).reserve(event.id(), 1);
        assertThat(store(NOW).initialize(event)).isFalse();
        assertStock(event.id(), 1, 1);
        Event changed = new Event(event.id(), "changed", 3, 3, event.startsAt(), event.endsAt());
        assertThatThrownBy(() -> store(NOW).initialize(changed)).hasMessageContaining("CONFIG_CONFLICT");
        Event partiallyIssued = new Event(IDS.incrementAndGet(), "issued", 2, 1,
                event.startsAt(), event.endsAt());
        assertCode(() -> store(NOW).initialize(partiallyIssued), INVALID_REQUEST);
    }

    @Test
    void periodIsStartInclusiveAndEndExclusiveAtMicrosecondPrecision() {
        Event event = event(2);
        store(NOW).initialize(event);
        assertCode(() -> store(event.startsAt().minusNanos(1000)).reserve(event.id(), 1), NOT_STARTED);
        store(event.startsAt()).reserve(event.id(), 1);
        store(event.endsAt().minusNanos(1000)).reserve(event.id(), 2);
        assertCode(() -> store(event.endsAt()).reserve(event.id(), 3), ENDED);
        assertStock(event.id(), 0, 2);
    }

    @Test
    void rejectsInvalidIdentifiersAndUninitializedEventWithoutCreatingKeys() {
        long id = IDS.incrementAndGet();
        assertCode(() -> store(NOW).reserve(id, 0), INVALID_REQUEST);
        assertCode(() -> store(NOW).reserve(-1, 1), INVALID_REQUEST);
        assertThatThrownBy(() -> store(NOW).reserve(id, 1)).hasMessageContaining("NOT_INITIALIZED");
        assertThat(redis.hasKey(state(id))).isFalse();
        assertThat(redis.hasKey(stream(id))).isFalse();
    }

    @Test
    void concurrentDifferentUsersReserveAtMostAvailableStock() throws Exception {
        Event event = event(10);
        store(NOW).initialize(event);
        List<String> results = concurrent(user -> {
            try {
                store(NOW).reserve(event.id(), user);
                return "CREATED";
            } catch (EventException e) {
                return e.code().name();
            }
        });
        assertThat(results).filteredOn("CREATED"::equals).hasSize(10);
        assertThat(results).filteredOn("SOLD_OUT"::equals).hasSize(30);
        assertStock(event.id(), 0, 10);
    }

    @Test
    void concurrentSameUserProducesOneReservationAndOneMessage() throws Exception {
        Event event = event(10);
        store(NOW).initialize(event);
        List<Reservation> results = concurrent(ignored -> store(NOW).reserve(event.id(), 1));
        assertThat(results).filteredOn(result -> !result.replayed()).hasSize(1);
        assertThat(results.stream().map(Reservation::messageId).distinct()).hasSize(1);
        assertStock(event.id(), 9, 1);
    }

    @Test
    void wrongStreamTypeFailsBeforeStockOrUserWrite() {
        Event event = event(1);
        store(NOW).initialize(event);
        redis.opsForValue().set(stream(event.id()), "wrong type");
        assertThatThrownBy(() -> store(NOW).reserve(event.id(), 1)).hasMessageContaining("STATE_INVALID");
        assertThat(redis.opsForHash().get(state(event.id()), "remaining")).isEqualTo("1");
        assertThat(redis.opsForHash().hasKey(state(event.id()), "user:1")).isFalse();
        assertThat(redis.opsForValue().get(stream(event.id()))).isEqualTo("wrong type");
    }

    @Test
    void rejectsCorruptedStockWithoutAppendingWork() {
        Event event = event(1);
        store(NOW).initialize(event);
        redis.opsForHash().put(state(event.id()), "remaining", "-1");
        assertThatThrownBy(() -> store(NOW).reserve(event.id(), 1)).hasMessageContaining("STATE_INVALID");
        assertThat(redis.opsForStream().size(stream(event.id()))).isZero();
        assertThat(redis.opsForHash().hasKey(state(event.id()), "user:1")).isFalse();
    }

    @Test
    void deniedStateWritePermissionFailsBeforeStreamAppend() {
        Event event = event(1);
        store(NOW).initialize(event);
        command("ACL", "SETUSER", "reservation-test", "on", ">test-only", "~*", "+@all", "-hset");
        RedisStandaloneConfiguration config = new RedisStandaloneConfiguration(
                REDIS.getHost(), REDIS.getMappedPort(6379));
        config.setUsername("reservation-test");
        config.setPassword("test-only");
        LettuceConnectionFactory restrictedFactory = new LettuceConnectionFactory(config);
        try {
            restrictedFactory.afterPropertiesSet();
            RedisReservationStore restricted = new RedisReservationStore(
                    new StringRedisTemplate(restrictedFactory), Clock.fixed(NOW, ZoneOffset.UTC));
            assertThatThrownBy(() -> restricted.reserve(event.id(), 1))
                    .hasMessageContaining("PERMISSION_DENIED");
            assertStock(event.id(), 1, 0);
        } finally {
            restrictedFactory.destroy();
            command("ACL", "SETUSER", "reservation-test", "off");
        }
    }

    @Test
    void detectsLostStreamAndDoesNotReserveMoreStock() {
        Event event = event(2);
        store(NOW).initialize(event);
        store(NOW).reserve(event.id(), 1);
        redis.delete(stream(event.id()));
        assertThatThrownBy(() -> store(NOW).reserve(event.id(), 2)).hasMessageContaining("STATE_INVALID");
        assertThat(redis.opsForHash().get(state(event.id()), "remaining")).isEqualTo("1");
        assertThat(redis.opsForHash().hasKey(state(event.id()), "user:2")).isFalse();
    }

    @Test
    void detectsLostStateAndRefusesAutomaticReinitialization() {
        Event event = event(2);
        store(NOW).initialize(event);
        store(NOW).reserve(event.id(), 1);
        redis.delete(state(event.id()));
        assertThatThrownBy(() -> store(NOW).reserve(event.id(), 2)).hasMessageContaining("NOT_INITIALIZED");
        assertThatThrownBy(() -> store(NOW).initialize(event)).hasMessageContaining("STATE_INVALID");
        assertThat(redis.opsForStream().size(stream(event.id()))).isEqualTo(1);
    }

    @Test
    void actualStreamAppendFailureLeavesStockAndUserUnchanged() {
        Event event = event(1);
        store(NOW).initialize(event);
        command("XGROUP", "CREATE", stream(event.id()), "test-group", "0", "MKSTREAM");
        // 자동 채번할 수 없는 최대 ID를 설정해 실제 Redis의 XADD 오류를 발생시킨다.
        command("XSETID", stream(event.id()), "18446744073709551615-18446744073709551615");
        assertThatThrownBy(() -> store(NOW).reserve(event.id(), 1))
                .hasMessageContaining("STREAM_WRITE_FAILED");
        assertStock(event.id(), 1, 0);
    }

    @Test
    void compensatesStreamEntryWhenStateWriteFailsInjectedAfterAppend() throws Exception {
        Event event = event(1);
        store(NOW).initialize(event);
        String original = new ClassPathResource("redis/reservation/reserve.lua")
                .getContentAsString(StandardCharsets.UTF_8);
        String injected = original.replace(
                "local updated = redis.pcall('HSET', KEYS[1],",
                "local updated = redis.pcall('HSET', KEYS[2],");
        assertThat(injected).isNotEqualTo(original);
        String result = redis.execute(new DefaultRedisScript<>(injected, String.class),
                List.of(state(event.id()), stream(event.id())), Long.toString(event.id()), "1",
                "2030-01-01T00:00:00.123456Z");
        assertThat(result).isEqualTo("STATE_WRITE_FAILED");
        assertStock(event.id(), 1, 0);
        store(NOW).reserve(event.id(), 1);
        assertStock(event.id(), 0, 1);
    }

    private static RedisReservationStore store(Instant now) {
        return new RedisReservationStore(redis, Clock.fixed(now, ZoneOffset.UTC));
    }

    private static Event event(int quantity) { return event(IDS.incrementAndGet(), quantity); }

    private static Event event(long id, int quantity) {
        return new Event(id, "Redis test", quantity, quantity, NOW.minusSeconds(60), NOW.plusSeconds(60));
    }

    private static String state(long id) { return "coupon:reservation:{" + id + "}:state"; }
    private static String stream(long id) { return "coupon:reservation:{" + id + "}:stream"; }

    private static void assertStock(long id, int remaining, int reservations) {
        assertThat(redis.opsForHash().get(state(id), "remaining")).isEqualTo(Integer.toString(remaining));
        assertThat(redis.opsForHash().size(state(id))).isEqualTo(4 + reservations);
        assertThat(redis.opsForStream().size(stream(id))).isEqualTo(reservations);
    }

    private static void command(String name, String... args) {
        redis.execute((RedisCallback<Object>) connection -> connection.execute(name,
                java.util.Arrays.stream(args).map(s -> s.getBytes(StandardCharsets.UTF_8))
                        .toArray(byte[][]::new)));
    }

    private static <T> List<T> concurrent(LongFunction<T> action) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<T>> futures = new ArrayList<>();
            for (long i = 1; i <= 40; i++) {
                long user = i;
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    return action.apply(user);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }

    private static void assertCode(Runnable action, EventException.Code code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(EventException.class,
                e -> assertThat(e.code()).isEqualTo(code));
    }
}
