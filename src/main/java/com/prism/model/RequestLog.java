package com.prism.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Table("request_logs")
public class RequestLog {
    @Id
    private Long id;

    @Column("key_id")
    private Long keyId;

    @Column("request_model")
    private String requestModel;

    @Column("resolved_model")
    private String resolvedModel;

    @Column("serving_model")
    private String servingModel;

    private String provider;
    private String status;

    @Column("error_code")
    private String errorCode;

    @Column("prompt_tokens")
    private int promptTokens;

    @Column("completion_tokens")
    private int completionTokens;

    @Column("cost_micro_usd")
    private long costMicroUsd;

    @Column("cache_result")
    private String cacheResult;

    private boolean fallback;

    @Column("fallback_reason")
    private String fallbackReason;

    @Column("latency_ms")
    private long latencyMs;

    @Column("created_at")
    private LocalDateTime createdAt;
}