package com.opspulse.common.exception;

/**
 * Thrown when an operation conflicts with existing state (e.g., duplicate email or slug).
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
