package com.redis.eventplatform;

import java.net.URI;
import com.redis.eventplatform.event.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/events")
class EventController {
    private final EventService service;

    EventController(EventService service) { this.service = service; }

    @PostMapping
    ResponseEntity<Event> create(@RequestBody CreateEvent command) {
        Event event = service.create(command);
        return ResponseEntity.created(URI.create("/api/events/" + event.id())).body(event);
    }

    @GetMapping("/{eventId}")
    Event get(@PathVariable long eventId) { return service.get(eventId); }

    @PostMapping("/{eventId}/issuances")
    ResponseEntity<Issuance> issue(@PathVariable long eventId, @RequestBody IssueRequest request) {
        if (request.userId() == null) {
            throw new EventException(EventException.Code.INVALID_REQUEST, "사용자 ID가 필요합니다.");
        }
        Issuance issuance = service.issue(eventId, request.userId());
        return ResponseEntity.created(URI.create("/api/events/" + eventId
                + "/issuances/" + issuance.userId())).body(issuance);
    }

    @GetMapping("/{eventId}/issuances/{userId}")
    Issuance getIssuance(@PathVariable long eventId, @PathVariable long userId) {
        return service.getIssuance(eventId, userId);
    }

    record IssueRequest(Long userId) {}
}
