package com.wzx.babiq.server.application.auth;

/** 已完成握手预留并绑定真实 WebSocket session 的可信桌面连接。 */
public record TrustedDesktopConnection(
        String reservationId,
        String desktopInstanceId,
        String desktopSessionId,
        String webSocketSessionId
) {

    public TrustedDesktopConnection {
        requireText(reservationId, "reservationId");
        requireText(desktopInstanceId, "desktopInstanceId");
        requireText(desktopSessionId, "desktopSessionId");
        requireText(webSocketSessionId, "webSocketSessionId");
    }

    @Override
    public String toString() {
        return "TrustedDesktopConnection(reservationId=[REDACTED], desktopInstanceId=[REDACTED], "
                + "desktopSessionId=[REDACTED], webSocketSessionId=[REDACTED])";
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
