-- OMNIS account requests, account controls and versioned system settings.
-- Existing accounts/passwords/roles are preserved.
CREATE TABLE system_setting (
  setting_key VARCHAR(80) PRIMARY KEY,
  value_json MEDIUMTEXT NOT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO system_setting(setting_key,value_json) VALUES
 ('security','{"failedLimit":5,"lockMinutes":15}'),
 ('ai','{"provider":null,"enabled":null,"externalAiEnabled":false,"externalAiConsentAt":null,"ollamaEndpoint":"http://127.0.0.1:11434","ollamaModel":"qwen3:8b","openaiModel":"","anthropicModel":"","timeoutSeconds":30,"dailyRequestLimit":50}');
CREATE TABLE account_security (
  account_id BIGINT PRIMARY KEY,
  service_no VARCHAR(20) NULL,
  manual_locked BOOLEAN NOT NULL DEFAULT FALSE,
  failed_attempts INT NOT NULL DEFAULT 0,
  lock_until DATETIME(6) NULL,
  last_login_at DATETIME(6) NULL,
  version BIGINT NOT NULL DEFAULT 0,
  UNIQUE KEY uk_security_service_no(service_no),
  CONSTRAINT fk_security_account FOREIGN KEY(account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE signup_request (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  login_id VARCHAR(50) NOT NULL,
  service_no VARCHAR(20) NOT NULL,
  display_name VARCHAR(50) NOT NULL,
  rank_code VARCHAR(20) NOT NULL,
  unit_name VARCHAR(100) NOT NULL,
  contact VARCHAR(30) NULL,
  reason VARCHAR(300) NULL,
  password_hash VARCHAR(100) NULL,
  status VARCHAR(12) NOT NULL DEFAULT 'PENDING',
  -- Nullable generated keys permit a new request after rejection, never duplicates while pending.
  pending_login VARCHAR(50) GENERATED ALWAYS AS (IF(status='PENDING',login_id,NULL)) STORED,
  pending_service VARCHAR(20) GENERATED ALWAYS AS (IF(status='PENDING',service_no,NULL)) STORED,
  reviewed_by BIGINT NULL,
  reviewed_at DATETIME(6) NULL,
  reject_reason VARCHAR(300) NULL,
  approved_role VARCHAR(20) NULL,
  account_id BIGINT NULL,
  version BIGINT NOT NULL DEFAULT 0,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  UNIQUE KEY uk_pending_login(pending_login),
  UNIQUE KEY uk_pending_service(pending_service),
  KEY ix_signup_status(status,created_at),
  CONSTRAINT fk_signup_reviewer FOREIGN KEY(reviewed_by) REFERENCES account(id),
  CONSTRAINT fk_signup_account FOREIGN KEY(account_id) REFERENCES account(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE system_audit (
  id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
  actor_id BIGINT NULL,
  action VARCHAR(60) NOT NULL,
  target VARCHAR(100) NOT NULL,
  outcome VARCHAR(10) NOT NULL DEFAULT 'SUCCESS',
  reason VARCHAR(300) NULL,
  details_json MEDIUMTEXT NULL,
  created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
  KEY ix_system_audit_created(created_at),
  KEY ix_system_audit_actor(actor_id,created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE ai_daily_usage (
  usage_date DATE PRIMARY KEY,
  request_count INT NOT NULL DEFAULT 0
) ENGINE=InnoDB;
