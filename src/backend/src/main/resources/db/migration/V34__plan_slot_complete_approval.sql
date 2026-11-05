-- 계획 칸 여러 개와 완료 결재 (G-148·G-149, DEC-115·116, slices/16)

-- 1. 계획 칸: 처리 건의 확정 계획(work_item_id) 또는 결재 문서의 계획(doc_id: 접수·등록 결재의 계획 제안, 완료 결재의 올릴 때 칸) 중 하나에 딸린다
CREATE TABLE plan_slot (
    id           BIGINT NOT NULL AUTO_INCREMENT,
    work_item_id BIGINT NULL,
    doc_id       BIGINT NULL,
    sort_order   INT    NOT NULL,          -- 시작 시각 → 끝 시각 → 보낸 순서 (1부터)
    start_date   DATE   NOT NULL,
    start_time   TIME   NULL,
    end_date     DATE   NOT NULL,          -- 종료예정일, 비우면 시작일을 넣는다(하루)
    end_time     TIME   NULL,
    PRIMARY KEY (id),
    KEY ix_plan_slot_item (work_item_id, sort_order),
    KEY ix_plan_slot_doc (doc_id, sort_order),
    CONSTRAINT fk_plan_slot_work_item FOREIGN KEY (work_item_id) REFERENCES work_item (id),
    CONSTRAINT fk_plan_slot_doc FOREIGN KEY (doc_id) REFERENCES approval_doc (id),
    CONSTRAINT ck_plan_slot_owner CHECK ((work_item_id IS NULL) <> (doc_id IS NULL))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE plan_slot_personnel (
    slot_id      BIGINT NOT NULL,
    personnel_id BIGINT NOT NULL,
    PRIMARY KEY (slot_id, personnel_id),
    KEY ix_psp_personnel (personnel_id),
    CONSTRAINT fk_psp_slot FOREIGN KEY (slot_id) REFERENCES plan_slot (id),
    CONSTRAINT fk_psp_personnel FOREIGN KEY (personnel_id) REFERENCES personnel (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE plan_slot_equipment (
    slot_id      BIGINT NOT NULL,
    equipment_id BIGINT NOT NULL,
    PRIMARY KEY (slot_id, equipment_id),
    KEY ix_pse_equipment (equipment_id),
    CONSTRAINT fk_pse_slot FOREIGN KEY (slot_id) REFERENCES plan_slot (id),
    CONSTRAINT fk_pse_equipment FOREIGN KEY (equipment_id) REFERENCES equipment (id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

-- 2. 지금 확정 계획(처리 건마다 기간 하나)을 칸 하나로 옮긴다. 계획을 정한 건(planned_by 있음)만. 접수 때 옮긴 희망 작업일은 계획이 아니다 (DEC-062)
INSERT INTO plan_slot (work_item_id, doc_id, sort_order, start_date, start_time, end_date, end_time)
SELECT w.id, NULL, 1, w.start_date, w.start_time, COALESCE(w.end_date, w.start_date), w.end_time
FROM work_item w WHERE w.planned_by_account_id IS NOT NULL AND w.start_date IS NOT NULL;
INSERT INTO plan_slot_personnel (slot_id, personnel_id)
SELECT s.id, p.personnel_id FROM work_item_personnel p JOIN plan_slot s ON s.work_item_id = p.work_item_id;
INSERT INTO plan_slot_equipment (slot_id, equipment_id)
SELECT s.id, e.equipment_id FROM work_item_equipment e JOIN plan_slot s ON s.work_item_id = e.work_item_id;

-- 3. 결재 문서의 계획 제안(접수·등록, 옛 지원 내용·계획 재결재)도 칸 하나로. 머리(plan_proposal)에는 자동 완료만 남긴다
INSERT INTO plan_slot (work_item_id, doc_id, sort_order, start_date, start_time, end_date, end_time)
SELECT NULL, p.doc_id, 1, p.start_date, p.start_time, p.end_date, p.end_time FROM plan_proposal p;
INSERT INTO plan_slot_personnel (slot_id, personnel_id)
SELECT s.id, p.personnel_id FROM plan_proposal_personnel p JOIN plan_slot s ON s.doc_id = p.doc_id;
INSERT INTO plan_slot_equipment (slot_id, equipment_id)
SELECT s.id, e.equipment_id FROM plan_proposal_equipment e JOIN plan_slot s ON s.doc_id = e.doc_id;

DROP TABLE plan_proposal_personnel;
DROP TABLE plan_proposal_equipment;
ALTER TABLE plan_proposal DROP COLUMN start_date, DROP COLUMN end_date, DROP COLUMN start_time, DROP COLUMN end_time;
DROP TABLE work_item_personnel;
DROP TABLE work_item_equipment;

-- 4. 자동 완료를 켠 사람·못 했다는 알림 시각·체계 자동 완료로 끝났는지 (REQ-110, F9 기한 넘긴 완료)
ALTER TABLE work_item
    ADD COLUMN auto_complete_by_account_id BIGINT   NULL AFTER auto_complete_at,
    ADD COLUMN auto_complete_notified_at   DATETIME NULL AFTER auto_complete_by_account_id,
    ADD COLUMN completed_by_auto           BOOLEAN  NOT NULL DEFAULT FALSE AFTER completed_at,
    ADD CONSTRAINT fk_work_item_auto_by FOREIGN KEY (auto_complete_by_account_id) REFERENCES account (id);
-- 켠 사람은 기록이 없으므로 계획을 정한 사람으로 둔다(13장 J5)
UPDATE work_item SET auto_complete_by_account_id = planned_by_account_id, updated_at = updated_at WHERE auto_complete = TRUE;
-- 지금 판정(WorkJudge.completionTime: 켜져 있고 지정 시각 ≤ 완료 시각)과 같게 옮겨 업무 현황 숫자가 바뀌지 않게
UPDATE work_item SET completed_by_auto = TRUE, updated_at = updated_at
WHERE status = 'DONE' AND auto_complete = TRUE AND auto_complete_at IS NOT NULL AND auto_complete_at <= completed_at;

-- 5. 결재 문서: 체계가 자동 완료로 올렸는가 (REQ-110 "결재 문서에 남긴다")
ALTER TABLE approval_doc ADD COLUMN auto_submitted BOOLEAN NOT NULL DEFAULT FALSE AFTER with_plan;

-- 6. 진행 중인 옛 지원 내용·계획 재결재: 문서만 취소한다(남은 단계는 PENDING 그대로, 하지 않은 회수 기록을 지어내지 않음).
--    처리 건은 결재 전으로(지원 내용 → 접수, 재결재 → 기존 계획 유지) (사용자 답, 14장 Q3). 알림은 만들거나 지우지 않는다(13장 J16)
UPDATE work_item w JOIN approval_doc d ON d.work_item_id = w.id
SET w.status = CASE d.doc_type WHEN 'SUPPORT' THEN 'APPROVED' ELSE 'PLANNED' END, w.updated_at = w.updated_at
WHERE d.doc_type IN ('SUPPORT', 'REPLAN') AND d.status = 'IN_PROGRESS' AND w.status = 'IN_APPROVAL';
UPDATE approval_doc SET status = 'CANCELLED', completed_at = NOW() WHERE doc_type IN ('SUPPORT', 'REPLAN') AND status = 'IN_PROGRESS';

-- 7. 계획 재결재 뒤 결재 없이 고친 표시(V21·V22)는 계획 재결재가 없어져 쓰지 않는다
ALTER TABLE work_item DROP FOREIGN KEY fk_work_item_plan_edited_doc;
ALTER TABLE work_item DROP COLUMN plan_edited_after_doc_id;

-- 8. 기본 결재 경로 (REQ-806): 종류는 발송·접수·완료. 지원 내용 경로를 완료 경로로 옮기고 계획 재결재 경로는 지운다 (14장 Q2)
DELETE s FROM approval_route_step s JOIN approval_route r ON r.id = s.route_id WHERE r.doc_type = 'REPLAN';
DELETE FROM approval_route WHERE doc_type = 'REPLAN';
UPDATE approval_route SET doc_type = 'COMPLETE' WHERE doc_type = 'SUPPORT';
