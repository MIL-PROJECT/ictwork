-- 등록된 업무 걸러 보기 (slices/11 3.6.1, 명세 REQ-117)
-- 처리 건이 끝난 시각(완료·반려·이송). 요청의 "완료한 날"(이송을 뺀 처리 건이 모두 끝났을 때 가장 늦게 끝난 날)에 쓴다
-- 반려·이송 시각은 전에는 남기지 않았다. 이미 끝난 건은 완료 시각, 없으면 마지막으로 바뀐 시각으로 채운다(updated_at은 그대로 둠)
ALTER TABLE work_item ADD COLUMN closed_at DATETIME NULL AFTER completed_at;
UPDATE work_item SET closed_at = COALESCE(completed_at, updated_at), updated_at = updated_at WHERE status IN ('DONE', 'REJECTED', 'TRANSFERRED');
