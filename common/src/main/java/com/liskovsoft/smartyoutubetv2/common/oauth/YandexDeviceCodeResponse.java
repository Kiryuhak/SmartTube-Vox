package com.liskovsoft.smartyoutubetv2.common.oauth;

/**
 * Ответ на POST https://oauth.yandex.com/device/code
 *
 * Поля соответствуют спецификации RFC 8628 и Яндекс OAuth Device Flow.
 */
public final class YandexDeviceCodeResponse {
    /** Код для идентификации устройства при polling. НЕ показывать пользователю. */
    private final String deviceCode;
    /** Короткий код, который пользователь вводит на ya.ru/device. */
    private final String userCode;
    /** URL для авторизации пользователя. Обычно https://ya.ru/device */
    private final String verificationUrl;
    /** Срок действия кода в секундах (обычно 600). */
    private final int expiresIn;
    /** Минимальный интервал polling в секундах (обычно 5). */
    private final int interval;

    public YandexDeviceCodeResponse(
            String deviceCode,
            String userCode,
            String verificationUrl,
            int expiresIn,
            int interval) {
        this.deviceCode = deviceCode;
        this.userCode = userCode;
        this.verificationUrl = verificationUrl;
        this.expiresIn = expiresIn;
        this.interval = interval;
    }

    public String getDeviceCode() {
        return deviceCode;
    }

    public String getUserCode() {
        return userCode;
    }

    public String getVerificationUrl() {
        return verificationUrl != null ? verificationUrl : "https://ya.ru/device";
    }

    public int getExpiresIn() {
        return expiresIn > 0 ? expiresIn : 600;
    }

    public int getInterval() {
        return interval > 0 ? interval : 5;
    }

    public boolean isValid() {
        return deviceCode != null && !deviceCode.isEmpty()
                && userCode != null && !userCode.isEmpty();
    }

    @Override
    public String toString() {
        // Намеренно НЕ включаем device_code: безопасность
        return "YandexDeviceCodeResponse{userCode=" + userCode
                + ", verificationUrl=" + getVerificationUrl()
                + ", expiresIn=" + expiresIn + "s"
                + ", interval=" + interval + "s}";
    }
}
