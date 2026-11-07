-- AI 업무지원 (REQ-C02·C08, slices/21 3.1·13.6, DEC-145): 업무지원 답의 화면 정보(JSON). 칸 값은 넣지 않는다.
-- 제안: {kind:"proposal", options, startedAt} / 채우기: {kind:"fill", startedAt, screen, target, filled, empty, asked} / 멈춤: {kind:"fill", stopped:true}
-- 사용자 메시지에는 사용자가 직접 적은 글(typed)만 둔다(근거 검사에서 체계가 만든 글을 빼려고, 13.3)
-- 제안 단추(고칠 대상 후보)는 개수 한도가 없어 TEXT(64KB)를 넘을 수 있으므로 MEDIUMTEXT
ALTER TABLE ai_message ADD COLUMN assist MEDIUMTEXT NULL AFTER used_sources;
