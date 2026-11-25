package com.dsuttz.yaba.torn;

/** Torn API error ({"error":{"code":..,"error":..}}) or a network/HTTP failure (code -1). */
public class TornApiException extends RuntimeException {

    public enum Kind { KEY, RATE, FATAL, RETRY }

    private final int code;

    public TornApiException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int code() {
        return code;
    }

    /** KEY = drop the key · RATE = slow down and retry · FATAL = don't retry · RETRY = try again. */
    public Kind kind() {
        return switch (code) {
            case 1, 2, 10, 13, 16, 18 -> Kind.KEY;
            case 5, 8 -> Kind.RATE;
            case 6, 7, 12, 21 -> Kind.FATAL;
            default -> Kind.RETRY;
        };
    }
}
