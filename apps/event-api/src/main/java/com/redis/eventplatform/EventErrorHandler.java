package com.redis.eventplatform;

import com.redis.eventplatform.event.EventException;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice(assignableTypes = EventController.class)
class EventErrorHandler {
    @ExceptionHandler(EventException.class)
    ResponseEntity<ApiError> handle(EventException exception) {
        HttpStatus status = switch (exception.code()) {
            case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
            case EVENT_NOT_FOUND, ISSUANCE_NOT_FOUND -> HttpStatus.NOT_FOUND;
            default -> HttpStatus.CONFLICT;
        };
        return ResponseEntity.status(status).body(new ApiError(exception.code().name(), exception.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ApiError> invalidBody(HttpMessageNotReadableException exception) {
        Throwable cause = exception.getMostSpecificCause();
        if (cause instanceof EventException domainException) return handle(domainException);
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "요청 본문을 확인하세요."));
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    ResponseEntity<ApiError> invalidPath() {
        return ResponseEntity.badRequest().body(new ApiError("INVALID_REQUEST", "식별자를 확인하세요."));
    }

    @ExceptionHandler(PessimisticLockingFailureException.class)
    ResponseEntity<ApiError> lockFailure() {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).header("Retry-After", "1")
                .body(new ApiError("RETRY_LATER", "일시적인 잠금 충돌입니다. 잠시 후 다시 요청하세요."));
    }

    record ApiError(String code, String message) {}
}
