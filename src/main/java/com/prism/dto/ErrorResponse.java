package com.prism.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ErrorResponse {

    private ErrorBody error;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ErrorBody {
        private String message;
        private String type;
        private String code;
    }

    public static ErrorResponse of(String code, String message) {
        return ErrorResponse.builder()
                .error(ErrorBody.builder()
                        .message(message)
                        .type("error")
                        .code(code)
                        .build())
                .build();
    }
}