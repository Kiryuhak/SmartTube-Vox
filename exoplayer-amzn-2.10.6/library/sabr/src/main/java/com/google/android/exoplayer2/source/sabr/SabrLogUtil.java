package com.google.android.exoplayer2.source.sabr;

final class SabrLogUtil {
    private SabrLogUtil() {}

    static String buildChunkLoadErrorMessage(String host, Exception error) {
        Throwable cause = error != null ? error.getCause() : null;
        return "Chunk load failed: error="
                + (error != null ? error.getClass().getSimpleName() : "unknown")
                + ", cause=" + (cause != null ? cause.getClass().getSimpleName() : "none")
                + ", host=" + (host != null ? host : "unknown");
    }
}
