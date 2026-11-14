package com.opspulse.common.exception;

/**
 * Thrown when an authenticated user lacks permission to perform an operation.
 */
public class ForbiddenException extends RuntimeException {

    public ForbiddenException(String message) {
        super(message);
    }
}
