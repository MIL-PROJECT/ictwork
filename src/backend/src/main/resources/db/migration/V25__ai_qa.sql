-- AI 질의응답 (slices/12 2.2·11.2, 명세 REQ-B01~B09, DEC-098)
-- 기능을 끈 서버(배포판)에도 표는 만든다(마이그레이션은 하나). 비어 있을 뿐이다
-- MySQL DDL은 트랜잭션이 아니어서 한 문장에 한 가지 변경만 쓴다(slices/11 2.1)

-- 색인 대상 하나: 파일·요청·양식 답·처리 건·구성도(편집 권한자용 FULL, 공유 권한자용 SHARED)
-- content_hash: 맞추기 루프의 비교 키(파일 sha256, 글의 sha256, 구성도 판 번호·이름·공유 항목). share_key: SHARED가 색인한 때의 공유 항목
CREATE TABLE ai_source (
    id           BIGINT        NOT NULL AUTO_INCREMENT,
    source_type  VARCHAR(20)   NOT NULL COMMENT 'FILE, WORK_REQUEST, WORK_FORM, WORK_ITEM, DIAGRAM',
    source_id    BIGINT        NOT NULL,
    variant      VARCHAR(10)   NOT NULL COMMENT '구성도 FULL·SHARED, 그 밖 -',
    content_hash CHAR(64)      NOT NULL,
    status       VARCHAR(10)   NOT NULL COMMENT 'PENDING, DONE, NO_TEXT, FAILED',
    error        VARCHAR(200)  NULL,
    title        VARCHAR(255)  NOT NULL,
    share_key    VARCHAR(1000) NULL,
    indexed_at   DATETIME(3)   NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_ai_source (source_type, source_id, variant),
    KEY ix_ai_source_status (status)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 조각: 단위(쪽·시트·슬라이드)마다 500자, 겹침 100자. embedding은 1024 × float32(작은 끝 순서)
CREATE TABLE ai_chunk (
    id        BIGINT      NOT NULL AUTO_INCREMENT,
    source_pk BIGINT      NOT NULL,
    seq       INT         NOT NULL,
    locator   VARCHAR(50) NULL COMMENT '예: 3쪽, 시트 점검표, 슬라이드 2',
    text      TEXT        NOT NULL,
    embedding BLOB        NOT NULL,
    PRIMARY KEY (id),
    KEY ix_ai_chunk_source (source_pk, seq),
    CONSTRAINT fk_ai_chunk_source FOREIGN KEY (source_pk) REFERENCES ai_source (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 글자 찾기(섞어 찾기의 한쪽): 한국어는 띄어쓰기로 낱말이 갈리지 않아 ngram으로 나눈다
CREATE FULLTEXT INDEX ft_ai_chunk_text ON ai_chunk (text) WITH PARSER ngram;

-- 대화: 본인만 본다(시스템 관리자도 남의 대화를 보지 않음, REQ-B01). 제목은 첫 질문 앞 30자
CREATE TABLE ai_conversation (
    id            BIGINT      NOT NULL AUTO_INCREMENT,
    account_id    BIGINT      NOT NULL,
    title         VARCHAR(30) NULL,
    created_at    DATETIME(3) NOT NULL,
    last_asked_at DATETIME(3) NULL,
    PRIMARY KEY (id),
    KEY ix_ai_conversation_account (account_id, last_asked_at),
    CONSTRAINT fk_ai_conversation_account FOREIGN KEY (account_id) REFERENCES account (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 메시지: 출처·조회 조건·확인할 곳은 JSON 글, pending_count는 "아직 읽는 중인 자료 N건"
CREATE TABLE ai_message (
    id              BIGINT      NOT NULL AUTO_INCREMENT,
    conversation_id BIGINT      NOT NULL,
    role            VARCHAR(10) NOT NULL COMMENT 'USER, ASSISTANT',
    content         MEDIUMTEXT  NOT NULL,
    sources         TEXT        NULL,
    conditions      TEXT        NULL,
    links           TEXT        NULL,
    pending_count   INT         NOT NULL DEFAULT 0,
    status          VARCHAR(10) NOT NULL COMMENT 'DONE, STOPPED, FAILED, TIMEOUT',
    created_at      DATETIME(3) NOT NULL,
    PRIMARY KEY (id),
    KEY ix_ai_message_conversation (conversation_id, id),
    CONSTRAINT fk_ai_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_conversation (id) ON DELETE CASCADE
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
