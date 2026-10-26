package com.travel.common;

public record Result<T>(String message, T data) {

    public static <T> Result<T> success(T data) {
        return new Result<>("success", data);
    }

    public static Result<Void> success() {
        return new Result<>("success", null);
    }

    public static Result<Void> failure(String message) {
        return new Result<>(message, null);
    }
}
