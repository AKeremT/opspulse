package com.opspulse.common.exception;

/**
 * Thrown when a request violates domain-level input rules (e.g., unsafe target URL).
 */
public class BadRequestException extends RuntimeException {

    public BadRequestException(String message) {
        super(message);
    }
}
