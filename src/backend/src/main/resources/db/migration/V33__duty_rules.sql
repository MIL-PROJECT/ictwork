-- 근무자 현황 로테이션 규칙 (REQ-404, G-147, slices/14 14.2)
-- 로테이션 줄을 규칙으로 바꾸고, 교대 근무표의 교대 설정을 교대 규칙 하나로 옮기고, 배치가 규칙을 가리키게 한다.
-- MySQL DDL은 트랜잭션이 아니므로 한 문장에 한 가지 변경만 쓴다(기본 키를 바꾸는 문장만 DROP·ADD를 한 문장에)

-- ① 줄 → 규칙
RENAME TABLE duty_line TO duty_rule;
ALTER TABLE duty_rule ADD COLUMN kind VARCHAR(10) NOT NULL DEFAULT 'WEEKDAY' AFTER roster_id;   -- WEEKDAY(요일 규칙)·SHIFT(교대 규칙), 바꾸지 않음
ALTER TABLE duty_rule ADD COLUMN name_key VARCHAR(30) COLLATE utf8mb4_bin NULL AFTER name;     -- 지우지 않은 규칙만 값(지운 규칙의 이름은 다시 씀)
ALTER TABLE duty_rule ADD COLUMN shift_pattern VARCHAR(500) NULL;                              -- 교대 규칙 패턴 JSON [근무 종류 id 또는 null(비번)]
ALTER TABLE duty_rule ADD COLUMN pattern_rev INT NOT NULL DEFAULT 0;                           -- 패턴이 바뀔 때 +1 (기억 이어 가기 판정)
ALTER TABLE duty_rule ADD CONSTRAINT ck_duty_rule_kind CHECK (kind IN ('WEEKDAY', 'SHIFT'));
-- 지금까지 줄 이름은 겹쳐도 됐다: 같은 근무표에서 대소문자 무시로 겹치는 지우지 않은 줄은 먼저 만든 것만 두고 나머지 이름 뒤에 " #id"
UPDATE duty_rule r
  JOIN duty_rule f ON f.roster_id = r.roster_id AND f.deleted_at IS NULL AND f.id < r.id
                  AND LOWER(f.name) COLLATE utf8mb4_bin = LOWER(r.name) COLLATE utf8mb4_bin
   SET r.name = CONCAT(LEFT(r.name, 30 - CHAR_LENGTH(CONCAT(' #', r.id))), ' #', r.id)
 WHERE r.deleted_at IS NULL;
UPDATE duty_rule SET name_key = LOWER(name) WHERE deleted_at IS NULL;
ALTER TABLE duty_rule ADD UNIQUE KEY uk_duty_rule_name (roster_id, name_key);

-- ② 교대 근무표의 설정 → 교대 규칙 하나 (교대 근무표에는 줄이 없다: DUTY_KIND_MISMATCH로 막혀 있었음)
INSERT INTO duty_rule (roster_id, kind, name, name_key, apply_days, shift_pattern, pattern_rev, created_at)
SELECT id, 'SHIFT', '교대', '교대', COALESCE(apply_days, 255), shift_pattern, pattern_rev, created_at
  FROM duty_roster WHERE kind = 'SHIFT';

-- ③ 근무 종류 → 규칙마다
ALTER TABLE duty_shift_type ADD COLUMN rule_id BIGINT NULL AFTER id;
UPDATE duty_shift_type t JOIN duty_rule r ON r.roster_id = t.roster_id AND r.kind = 'SHIFT' SET t.rule_id = r.id;
ALTER TABLE duty_shift_type MODIFY rule_id BIGINT NOT NULL;
ALTER TABLE duty_shift_type ADD UNIQUE KEY uk_duty_shift_type_rule_name (rule_id, name_key);
ALTER TABLE duty_shift_type ADD UNIQUE KEY uk_duty_shift_type_rule_short (rule_id, short_key);
ALTER TABLE duty_shift_type ADD CONSTRAINT fk_duty_shift_type_rule FOREIGN KEY (rule_id) REFERENCES duty_rule (id) ON DELETE CASCADE;
ALTER TABLE duty_shift_type DROP FOREIGN KEY fk_duty_shift_type_roster;
ALTER TABLE duty_shift_type DROP INDEX uk_duty_shift_type_name;
ALTER TABLE duty_shift_type DROP INDEX uk_duty_shift_type_short;
ALTER TABLE duty_shift_type DROP COLUMN roster_id;

-- ④ 교대 인원 → 규칙마다
ALTER TABLE duty_shift_member ADD COLUMN rule_id BIGINT NULL FIRST;
UPDATE duty_shift_member m JOIN duty_rule r ON r.roster_id = m.roster_id AND r.kind = 'SHIFT' SET m.rule_id = r.id;
ALTER TABLE duty_shift_member MODIFY rule_id BIGINT NOT NULL;
ALTER TABLE duty_shift_member DROP FOREIGN KEY fk_duty_shift_member_roster;
ALTER TABLE duty_shift_member DROP PRIMARY KEY, ADD PRIMARY KEY (rule_id, personnel_id);
ALTER TABLE duty_shift_member ADD CONSTRAINT fk_duty_shift_member_rule FOREIGN KEY (rule_id) REFERENCES duty_rule (id) ON DELETE CASCADE;
ALTER TABLE duty_shift_member DROP COLUMN roster_id;

-- ⑤ 패턴 칸 기억 → 규칙마다
ALTER TABLE duty_shift_memory ADD COLUMN rule_id BIGINT NULL FIRST;
UPDATE duty_shift_memory m JOIN duty_rule r ON r.roster_id = m.roster_id AND r.kind = 'SHIFT' SET m.rule_id = r.id;
ALTER TABLE duty_shift_memory MODIFY rule_id BIGINT NOT NULL;
ALTER TABLE duty_shift_memory DROP FOREIGN KEY fk_duty_shift_memory_roster;
ALTER TABLE duty_shift_memory DROP PRIMARY KEY, ADD PRIMARY KEY (rule_id, personnel_id, duty_date);
ALTER TABLE duty_shift_memory DROP INDEX ix_duty_shift_memory_date;
ALTER TABLE duty_shift_memory ADD KEY ix_duty_shift_memory_rule_date (rule_id, duty_date);
ALTER TABLE duty_shift_memory ADD CONSTRAINT fk_duty_shift_memory_rule FOREIGN KEY (rule_id) REFERENCES duty_rule (id) ON DELETE CASCADE;
ALTER TABLE duty_shift_memory DROP COLUMN roster_id;

-- ⑥ 줄 인원 → 요일 규칙 인원
RENAME TABLE duty_line_member TO duty_rule_member;
ALTER TABLE duty_rule_member DROP FOREIGN KEY fk_duty_line_member_line;
ALTER TABLE duty_rule_member RENAME COLUMN line_id TO rule_id;
ALTER TABLE duty_rule_member ADD CONSTRAINT fk_duty_rule_member_rule FOREIGN KEY (rule_id) REFERENCES duty_rule (id) ON DELETE CASCADE;

-- ⑦ 배치가 규칙을 가리킨다. 요일 규칙 배치는 weekday_rule_id(= rule_id)로 규칙·날마다 1명을 DB가 막는다(지금 line_id와 같은 뜻)
ALTER TABLE duty_placement ADD COLUMN rule_id BIGINT NULL AFTER duty_date;
UPDATE duty_placement SET rule_id = line_id WHERE line_id IS NOT NULL;
UPDATE duty_placement p JOIN duty_shift_type t ON t.id = p.shift_type_id SET p.rule_id = t.rule_id WHERE p.shift_type_id IS NOT NULL;
ALTER TABLE duty_placement MODIFY rule_id BIGINT NOT NULL;
ALTER TABLE duty_placement ADD KEY ix_duty_placement_rule (rule_id, duty_date);
ALTER TABLE duty_placement ADD CONSTRAINT fk_duty_placement_rule FOREIGN KEY (rule_id) REFERENCES duty_rule (id) ON DELETE CASCADE;
ALTER TABLE duty_placement DROP FOREIGN KEY fk_duty_placement_line;
ALTER TABLE duty_placement RENAME COLUMN line_id TO weekday_rule_id;
ALTER TABLE duty_placement RENAME INDEX uk_duty_placement_line TO uk_duty_placement_weekday;
ALTER TABLE duty_placement ADD CONSTRAINT fk_duty_placement_weekday_rule FOREIGN KEY (weekday_rule_id) REFERENCES duty_rule (id) ON DELETE CASCADE;

-- ⑧ 근무표에서 방식·교대 설정을 뺀다
ALTER TABLE duty_roster DROP CHECK ck_duty_roster_kind;
ALTER TABLE duty_roster DROP COLUMN kind;
ALTER TABLE duty_roster DROP COLUMN apply_days;
ALTER TABLE duty_roster DROP COLUMN shift_pattern;
ALTER TABLE duty_roster DROP COLUMN pattern_rev;
