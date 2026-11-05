-- 결재 없이 계획을 고친 처리 건 (명세 REQ-209, G-83, 재검토 N5)
-- 계획을 결재 없이 고칠 때(DEC-064) 그 처리 건의 가장 최근 결재 문서. 그 문서가 반려·회수된 계획 재결재면 계획이 다른 방법으로 바뀌었으므로
-- 더는 다시 올리기를 기다리지 않는다(기안자의 처리할 문서에서 빠지고 다시 올릴 수 없음). 그 뒤 새 결재를 올리면 그 문서가 가장 최근이라 상관없다
ALTER TABLE work_item ADD COLUMN plan_edited_after_doc_id BIGINT NULL AFTER closed_at;
