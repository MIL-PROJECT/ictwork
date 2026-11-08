-- 개인설정 설명 글 보이기 (REQ-009, DEC-152, slices/23): 처음은 켬. 계정에 저장해 어느 PC에서 로그인해도 같다
ALTER TABLE account ADD COLUMN show_guides BOOLEAN NOT NULL DEFAULT TRUE;
