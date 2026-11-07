-- 개인설정 글자 크기 (REQ-008, DEC-124, slices/18): 보통·크게, 처음은 보통. 계정에 저장해 어느 PC에서 로그인해도 같다
ALTER TABLE account ADD COLUMN font_size VARCHAR(10) NOT NULL DEFAULT 'NORMAL';
ALTER TABLE account ADD CONSTRAINT ck_account_font_size CHECK (font_size IN ('NORMAL', 'LARGE'));
