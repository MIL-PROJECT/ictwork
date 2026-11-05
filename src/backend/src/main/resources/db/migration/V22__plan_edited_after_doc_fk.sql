-- V21 열 work_item.plan_edited_after_doc_id에 외래 키 (재검토 F9-2): 있는 결재 문서만 가리킨다. 결재 문서는 지우지 않으므로 지울 때 동작은 두지 않는다
ALTER TABLE work_item ADD CONSTRAINT fk_work_item_plan_edited_doc FOREIGN KEY (plan_edited_after_doc_id) REFERENCES approval_doc (id);
