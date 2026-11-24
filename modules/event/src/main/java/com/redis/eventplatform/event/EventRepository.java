package com.redis.eventplatform.event;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.stereotype.Repository;

@Repository
public class EventRepository {
    private final JdbcTemplate jdbc;

    public EventRepository(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    public long create(CreateEvent command) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO events (name, total_quantity, remaining_quantity, starts_at, ends_at)
                    VALUES (?, ?, ?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, command.name());
            statement.setInt(2, command.quantity());
            statement.setInt(3, command.quantity());
            statement.setObject(4, utc(command.startsAt()));
            statement.setObject(5, utc(command.endsAt()));
            return statement;
        }, key);
        return key.getKey().longValue();
    }

    public Optional<Event> find(long id, boolean lock) {
        return jdbc.query("SELECT * FROM events WHERE id = ?" + (lock ? " FOR UPDATE" : ""),
                (rs, row) -> new Event(rs.getLong("id"), rs.getString("name"),
                        rs.getInt("total_quantity"), rs.getInt("remaining_quantity"),
                        instant(rs, "starts_at"), instant(rs, "ends_at")), id).stream().findFirst();
    }

    public Optional<Issuance> findIssuance(long eventId, long userId) {
        return jdbc.query("SELECT * FROM coupon_issuances WHERE event_id = ? AND user_id = ?",
                (rs, row) -> new Issuance(rs.getLong("id"), rs.getLong("event_id"),
                        rs.getLong("user_id"), instant(rs, "confirmed_at")), eventId, userId)
                .stream().findFirst();
    }

    public int decreaseStock(long eventId) {
        return jdbc.update("""
                UPDATE events SET remaining_quantity = remaining_quantity - 1
                WHERE id = ? AND remaining_quantity > 0
                """, eventId);
    }

    public Issuance insertIssuance(long eventId, long userId, Instant now) {
        var key = new GeneratedKeyHolder();
        jdbc.update(connection -> {
            PreparedStatement statement = connection.prepareStatement("""
                    INSERT INTO coupon_issuances (event_id, user_id, confirmed_at) VALUES (?, ?, ?)
                    """, Statement.RETURN_GENERATED_KEYS);
            statement.setLong(1, eventId);
            statement.setLong(2, userId);
            statement.setObject(3, utc(now));
            return statement;
        }, key);
        return new Issuance(key.getKey().longValue(), eventId, userId, now);
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, LocalDateTime.class).toInstant(ZoneOffset.UTC);
    }
}
