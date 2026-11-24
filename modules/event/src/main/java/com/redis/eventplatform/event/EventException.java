package com.redis.eventplatform.event;

public class EventException extends RuntimeException {
    public enum Code { INVALID_REQUEST, EVENT_NOT_FOUND, ISSUANCE_NOT_FOUND,
        NOT_STARTED, ENDED, SOLD_OUT, ALREADY_ISSUED }

    private final Code code;

    public EventException(Code code, String message) {
        super(message);
        this.code = code;
    }

    public Code code() { return code; }
}
