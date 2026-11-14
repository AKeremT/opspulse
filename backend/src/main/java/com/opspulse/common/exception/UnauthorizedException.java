package com.opspulse.common.exception;

/**
 * Thrown when authentication fails or a token is invalid, expired, or revoked.
 */
public class UnauthorizedException extends RuntimeException {

    public UnauthorizedException(String message) {
        super(message);
    }
}
