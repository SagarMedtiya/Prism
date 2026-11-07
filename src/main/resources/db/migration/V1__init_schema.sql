CREATE TABLE virtual_keys (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_hash VARCHAR(128) NOT NULL UNIQUE,
    name VARCHAR(100) NOT NULL,
    team VARCHAR(100),
    model_allowlist JSON NOT NULL,
    rpm_limit INT NOT NULL DEFAULT 60,
    monthly_budget_micro_usd BIGINT NOT NULL DEFAULT 10000000,
    status ENUM('active', 'disabled') NOT NULL DEFAULT 'active',
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    INDEX idx_key_hash (key_hash),
    INDEX idx_status (status)
);

CREATE TABLE usage_records (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_id BIGINT NOT NULL,
    model VARCHAR(100) NOT NULL,
    provider VARCHAR(50),
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    cost_micro_usd BIGINT NOT NULL DEFAULT 0,
    cache_result ENUM('hit', 'miss', 'bypass') NOT NULL DEFAULT 'miss',
    fallback BOOLEAN NOT NULL DEFAULT FALSE,
    latency_ms BIGINT NOT NULL DEFAULT 0,
    status VARCHAR(30) NOT NULL DEFAULT 'success',
    usage_estimated BOOLEAN NOT NULL DEFAULT FALSE,
    tokens_saved INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_key_id (key_id),
    INDEX idx_created_at (created_at),
    INDEX idx_key_created (key_id, created_at),
    FOREIGN KEY (key_id) REFERENCES virtual_keys(id)
);

CREATE TABLE request_logs (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_id BIGINT,
    request_model VARCHAR(100),
    resolved_model VARCHAR(100),
    serving_model VARCHAR(100),
    provider VARCHAR(50),
    status VARCHAR(30) NOT NULL,
    error_code VARCHAR(50),
    prompt_tokens INT DEFAULT 0,
    completion_tokens INT DEFAULT 0,
    cost_micro_usd BIGINT DEFAULT 0,
    cache_result VARCHAR(10),
    fallback BOOLEAN DEFAULT FALSE,
    fallback_reason VARCHAR(200),
    latency_ms BIGINT DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_key_id (key_id),
    INDEX idx_status (status),
    INDEX idx_created_at (created_at)
);

CREATE TABLE cache_entries (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    key_id BIGINT NOT NULL,
    prompt_hash VARCHAR(64) NOT NULL,
    embedding BLOB,
    model VARCHAR(100) NOT NULL,
    response_body MEDIUMTEXT NOT NULL,
    prompt_tokens INT NOT NULL DEFAULT 0,
    completion_tokens INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    INDEX idx_key_id (key_id),
    INDEX idx_key_prompt (key_id, prompt_hash),
    INDEX idx_expires (expires_at),
    FOREIGN KEY (key_id) REFERENCES virtual_keys(id)
);