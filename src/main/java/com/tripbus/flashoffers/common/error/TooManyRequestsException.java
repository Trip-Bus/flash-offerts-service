package com.tripbus.flashoffers.common.error;

import org.springframework.http.HttpStatus;

import java.util.Map;

/** 429 con la cabecera Retry-After (segundos hasta que se puede volver a intentar). */
public class TooManyRequestsException extends ApiException {

    private final long retryAfterSeconds;

    public TooManyRequestsException(String code, String message, long retryAfterSeconds) {
        super(HttpStatus.TOO_MANY_REQUESTS, code, message, Map.of("retryAfterSeconds", retryAfterSeconds));
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
