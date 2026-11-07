-- 교대 근무 종류의 쉬는 근무 표시 (REQ-404, G-151, slices/19 14.1)
-- 늘 있던 비번(패턴 칸 null)을 없애고, 패턴에 null이 있는 교대 규칙마다 쉬는 근무 "비번"(비, 인원 0)을 만들어 null을 그 id로 바꾼다.
-- 칸 위치가 같으므로 pattern_rev·칸 기억은 그대로. "비번"·"비"는 지금까지 근무 종류로 막혀 있어 겹치지 않는다
ALTER TABLE duty_shift_type ADD COLUMN off_duty BOOLEAN NOT NULL DEFAULT FALSE;

INSERT INTO duty_shift_type (rule_id, name, name_key, short_label, short_key, headcount, off_duty)
SELECT id, '비번', '비번', '비', '비', 0, TRUE FROM duty_rule
WHERE kind = 'SHIFT' AND shift_pattern LIKE '%null%';

UPDATE duty_rule r JOIN duty_shift_type t ON t.rule_id = r.id AND t.off_duty
SET r.shift_pattern = REPLACE(r.shift_pattern, 'null', CAST(t.id AS CHAR))
WHERE r.kind = 'SHIFT' AND r.shift_pattern LIKE '%null%';
