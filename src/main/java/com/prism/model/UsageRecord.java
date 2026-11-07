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
@Table("usage_records")
public class UsageRecord {
    @Id
    private Long id;

    @Column("key_id")
    private Long keyId;

    private String model;
    private String provider;

    @Column("prompt_tokens")
    private int promptTokens;

    @Column("completion_tokens")
    private int completionTokens;

    @Column("cost_micro_usd")
    private long costMicroUsd;

    @Column("cache_result")
    private String cacheResult;

    private boolean fallback;

    @Column("latency_ms")
    private long latencyMs;

    private String status;

    @Column("usage_estimated")
    private boolean usageEstimated;

    @Column("tokens_saved")
    private int tokensSaved;

    @Column("created_at")
    private LocalDateTime createdAt;
}