package io.trustagent.auth.dto;

public record LoginResponse(String token, String tokenType, long expiresInSeconds, UserInfoView user) {

    public static LoginResponse bearer(String token, long expiresInSeconds, UserInfoView user) {
        return new LoginResponse(token, "Bearer", expiresInSeconds, user);
    }
}
