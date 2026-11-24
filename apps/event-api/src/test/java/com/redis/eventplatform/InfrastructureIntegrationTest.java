package com.redis.eventplatform;

import java.time.Duration;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.net.URI;
import java.net.http.*;
import com.redis.eventplatform.event.*;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import static org.mockito.Mockito.*;
import org.flywaydb.core.Flyway;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.transaction.annotation.Transactional;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.mysql.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Tag("integration")
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InfrastructureIntegrationTest {
    @Container @ServiceConnection
    static final MySQLContainer MYSQL = new MySQLContainer("mysql:8.4.13")
            .withCommand("--character-set-server=utf8mb4", "--collation-server=utf8mb4_0900_ai_ci",
                    "--default-time-zone=+00:00");
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:8.2.11-alpine"))
            .withExposedPorts(6379);
    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }
    @LocalServerPort int port;
    @Autowired EventService eventService;
    @MockitoSpyBean EventRepository eventRepository;
    @MockitoBean Clock clock;
    private static final Instant NOW = Instant.parse("2030-01-01T00:00:00Z");

    @BeforeEach
    void setClock() { when(clock.instant()).thenReturn(NOW); }

    @Autowired Flyway flyway;
    @Autowired JdbcTemplate jdbc;
    @Autowired StringRedisTemplate redis;

    @Test
    void connectsToMysqlAndRedis() {
        assertThat(jdbc.queryForObject("select 1", Integer.class)).isEqualTo(1);
        assertThat(jdbc.queryForObject("select version()", String.class)).startsWith("8.4.13");
        assertThat(jdbc.queryForObject("select @@character_set_server", String.class)).isEqualTo("utf8mb4");
        assertThat(jdbc.queryForObject("select @@global.time_zone", String.class)).isEqualTo("+00:00");
        String key = "test:bootstrap:" + java.util.UUID.randomUUID();
        try {
            redis.opsForValue().set(key, "ready", Duration.ofSeconds(30));
            assertThat(redis.opsForValue().get(key)).isEqualTo("ready");
        } finally {
            redis.delete(key);
        }
    }

    @Test
    void appliesMigrationsOnceAndValidatesHistory() {
        flyway.validate();
        assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("2");
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    @Transactional
    void rejectsDuplicateConfirmationForSameEventAndUser() {
        long eventId = createEvent();
        jdbc.update("INSERT INTO coupon_issuances (event_id, user_id) VALUES (?, ?)", eventId, 1L);
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO coupon_issuances (event_id, user_id) VALUES (?, ?)", eventId, 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
        jdbc.update("INSERT INTO coupon_issuances (event_id, user_id) VALUES (?, ?)", eventId, 2L);
        long otherEventId = createEvent();
        jdbc.update("INSERT INTO coupon_issuances (event_id, user_id) VALUES (?, ?)", otherEventId, 1L);
    }

    @Test
    @Transactional
    void rejectsIssuanceForMissingEvent() {
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO coupon_issuances (event_id, user_id) VALUES (?, ?)", -1L, 1L))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @Transactional
    void rejectsDeletingEventWithConfirmedIssuance() {
        long eventId = createEvent();
        jdbc.update("INSERT INTO coupon_issuances (event_id, user_id) VALUES (?, ?)", eventId, 1L);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM events WHERE id = ?", eventId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM events WHERE id = ?", Long.class, eventId)).isEqualTo(1L);
        assertThat(jdbc.queryForObject(
                "SELECT COUNT(*) FROM coupon_issuances WHERE event_id = ?", Long.class, eventId))
                .isEqualTo(1L);
    }

    @Test
    void issuesAndRetrievesCouponAndRejectsDuplicateWithoutLosingStock() {
        Event event = activeEvent(2);
        Issuance issuance = eventService.issue(event.id(), 10);
        assertThat(eventService.getIssuance(event.id(), 10)).isEqualTo(issuance);
        assertCode(() -> eventService.issue(event.id(), 10), EventException.Code.ALREADY_ISSUED);
        assertThat(eventService.get(event.id()).remainingQuantity()).isEqualTo(1);
        eventService.issue(event.id(), 11);
        assertCode(() -> eventService.issue(event.id(), 12), EventException.Code.SOLD_OUT);
        assertThat(eventService.get(event.id()).remainingQuantity()).isZero();
    }

    @Test
    void respectsPeriodBoundariesAndValidatesIdentifiers() {
        Event event = activeEvent(2);
        when(clock.instant()).thenReturn(event.startsAt().minusNanos(1000));
        assertCode(() -> eventService.issue(event.id(), 1), EventException.Code.NOT_STARTED);
        when(clock.instant()).thenReturn(event.startsAt());
        eventService.issue(event.id(), 1);
        when(clock.instant()).thenReturn(event.endsAt());
        assertCode(() -> eventService.issue(event.id(), 2), EventException.Code.ENDED);
        assertCode(() -> eventService.issue(event.id(), 0), EventException.Code.INVALID_REQUEST);
        assertCode(() -> eventService.issue(-1, 2), EventException.Code.INVALID_REQUEST);
        assertCode(() -> eventService.issue(Long.MAX_VALUE, 2), EventException.Code.EVENT_NOT_FOUND);
        assertCode(() -> eventService.getIssuance(event.id(), 99), EventException.Code.ISSUANCE_NOT_FOUND);
        assertThat(eventService.get(event.id()).remainingQuantity()).isEqualTo(1);
    }

    @Test
    void rollsBackStockWhenIssuanceInsertFails() {
        Event event = activeEvent(1);
        doThrow(new DataIntegrityViolationException("injected insert failure"))
                .when(eventRepository).insertIssuance(eq(event.id()), eq(1L), any());
        assertThatThrownBy(() -> eventService.issue(event.id(), 1))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(eventService.get(event.id()).remainingQuantity()).isEqualTo(1);
        assertThat(eventRepository.findIssuance(event.id(), 1)).isEmpty();
    }

    @Test
    void concurrentRequestsNeverExceedStock() throws Exception {
        Event event = activeEvent(10);
        List<String> results = concurrentIssues(event.id(), false);
        assertThat(results.stream().filter("CONFIRMED"::equals).count()).isEqualTo(10);
        assertThat(results.stream().filter("SOLD_OUT"::equals).count()).isEqualTo(30);
        assertThat(eventService.get(event.id()).remainingQuantity()).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_issuances WHERE event_id = ?",
                Long.class, event.id())).isEqualTo(10L);
    }

    @Test
    void concurrentDuplicateRequestsConfirmOnce() throws Exception {
        Event event = activeEvent(10);
        List<String> results = concurrentIssues(event.id(), true);
        assertThat(results.stream().filter("CONFIRMED"::equals).count()).isEqualTo(1);
        assertThat(results.stream().filter("ALREADY_ISSUED"::equals).count()).isEqualTo(39);
        assertThat(eventService.get(event.id()).remainingQuantity()).isEqualTo(9);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM coupon_issuances WHERE event_id = ?",
                Long.class, event.id())).isEqualTo(1L);
    }

    @Test
    void httpApiCreatesIssuesAndReturnsErrors() throws Exception {
        HttpResponse<String> created = request("POST", "/api/events", """
                {"name":"HTTP event","quantity":1,
                 "startsAt":"2029-12-31T00:00:00Z","endsAt":"2030-01-02T00:00:00Z"}
                """);
        assertThat(created.statusCode()).isEqualTo(201);
        String location = created.headers().firstValue("Location").orElseThrow();
        assertThat(request("GET", location, null).statusCode()).isEqualTo(200);
        HttpResponse<String> issued = request("POST", location + "/issuances", "{\"userId\":1}");
        assertThat(issued.statusCode()).isEqualTo(201);
        assertThat(request("GET", issued.headers().firstValue("Location").orElseThrow(), null).statusCode())
                .isEqualTo(200);
        HttpResponse<String> duplicate = request("POST", location + "/issuances", "{\"userId\":1}");
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("ALREADY_ISSUED");
        assertThat(request("POST", location + "/issuances", "{\"userId\":2}").body()).contains("SOLD_OUT");
        assertThat(request("POST", location + "/issuances", "{\"userId\":0}").statusCode()).isEqualTo(400);
        assertThat(request("POST", location + "/issuances", "{}").statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/events", "{}").statusCode()).isEqualTo(400);
        assertThat(request("POST", "/api/events", "{").statusCode()).isEqualTo(400);
        assertThat(request("GET", "/api/events/9223372036854775807", null).statusCode()).isEqualTo(404);
    }

    @Test
    void httpReturnsRetryableErrorForLockFailure() throws Exception {
        Event event = activeEvent(1);
        doThrow(new org.springframework.dao.CannotAcquireLockException("injected lock timeout"))
                .when(eventRepository).find(event.id(), true);
        HttpResponse<String> response = request("POST", "/api/events/" + event.id() + "/issuances",
                "{\"userId\":1}");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.headers().firstValue("Retry-After")).contains("1");
        assertThat(response.body()).contains("RETRY_LATER");
        assertThat(eventService.get(event.id()).remainingQuantity()).isEqualTo(1);
    }

    private Event activeEvent(int quantity) {
        return eventService.create(new CreateEvent("test", quantity, NOW.minusSeconds(60), NOW.plusSeconds(60)));
    }

    private List<String> concurrentIssues(long eventId, boolean sameUser) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(16)) {
            List<Future<String>> futures = new ArrayList<>();
            for (int i = 1; i <= 40; i++) {
                long userId = sameUser ? 1 : i;
                futures.add(pool.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start timeout");
                    try {
                        eventService.issue(eventId, userId);
                        return "CONFIRMED";
                    } catch (EventException exception) {
                        return exception.code().name();
                    }
                }));
            }
            start.countDown();
            List<String> results = new ArrayList<>();
            for (Future<String> future : futures) results.add(future.get(20, TimeUnit.SECONDS));
            return results;
        }
    }

    private HttpResponse<String> request(String method, String path, String json) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(10));
        if (json == null) builder.GET();
        else builder.header("Content-Type", "application/json")
                .method(method, HttpRequest.BodyPublishers.ofString(json));
        try (HttpClient client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static void assertCode(Runnable action, EventException.Code code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(EventException.class,
                exception -> assertThat(exception.code()).isEqualTo(code));
    }

    private long createEvent() {
        jdbc.update("""
                INSERT INTO events (name, total_quantity, remaining_quantity, starts_at, ends_at)
                VALUES ('test event', 10, 10, '2026-01-01 00:00:00', '2027-01-01 00:00:00')
                """);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
