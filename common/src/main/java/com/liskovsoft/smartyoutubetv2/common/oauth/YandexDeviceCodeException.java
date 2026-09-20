package com.liskovsoft.smartyoutubetv2.common.oauth;

import java.io.IOException;

/**
 * Исключение при ошибке Яндекс Device Code API.
 * Используется для не-polling ошибок (device/code endpoint).
 */
public class YandexDeviceCodeException extends IOException {
    private final int httpCode;

    public YandexDeviceCodeException(int httpCode, String message) {
        super(message);
        this.httpCode = httpCode;
    }

    public int getHttpCode() {
        return httpCode;
    }
}
