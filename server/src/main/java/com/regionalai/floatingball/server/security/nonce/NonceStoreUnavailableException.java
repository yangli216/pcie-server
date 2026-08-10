package com.regionalai.floatingball.server.security.nonce;

public class NonceStoreUnavailableException extends RuntimeException {

    public NonceStoreUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
