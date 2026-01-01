package com.github.dimitryivaniuta.gateway.sample.dto;

public class DemoTransientException extends RuntimeException {
    public DemoTransientException(String message) {
        super(message);
    }
}
