package io.github.easylsp.api.internal;

/** Stable machine-readable runtime failure, transported separately from its display message. */
public final class OperationException extends RuntimeException {
    public final String code;
    public OperationException(String code, String message) { super(message); this.code = code; }
}
