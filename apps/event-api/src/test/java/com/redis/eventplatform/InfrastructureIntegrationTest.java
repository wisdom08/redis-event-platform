package com.redis.eventplatform;

import java.time.Duration;
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

    private long createEvent() {
        jdbc.update("""
                INSERT INTO events (name, total_quantity, remaining_quantity, starts_at, ends_at)
                VALUES ('test event', 10, 10, '2026-01-01 00:00:00', '2027-01-01 00:00:00')
                """);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
