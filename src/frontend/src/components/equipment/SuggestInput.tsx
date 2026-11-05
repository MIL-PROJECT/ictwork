import { fetchSuggestions, type SuggestField } from "@/api/equipment";
import Input from "@/components/form/input/InputField";
import { clip } from "@/utils";
import { useEffect, useId, useState } from "react";

// 입력 추천 칸 (REQ-515, slices/10 8장): 글자를 치면 0.3초 뒤 그 글자가 든 이전 입력값을 서버에 묻고,
// 브라우저 기본 추천 목록(datalist)으로 보인다. 추천에 없는 값도 그대로 적는다
export default function SuggestInput({
  id,
  field,
  value,
  onChange,
  invalid,
  describedBy,
}: {
  id: string;
  field: SuggestField;
  value: string;
  onChange: (value: string) => void;
  invalid?: boolean;
  describedBy?: string;
}) {
  const listId = useId();
  const [options, setOptions] = useState<string[]>([]);
  // 사용자가 이 칸에 글자를 친 뒤에만 묻는다(고치기 화면을 열 때 칸마다 묻지 않게)
  const [typed, setTyped] = useState(false);

  useEffect(() => {
    const q = value.trim();
    if (!typed || !q) return;
    const controller = new AbortController();
    const timer = window.setTimeout(() => {
      fetchSuggestions(field, q, controller.signal).then(
        (list) => setOptions(list.filter((v) => v !== value)),
        () => setOptions([]), // 추천은 보조 기능이라 실패하면 목록만 비운다
      );
    }, 300);
    return () => {
      window.clearTimeout(timer);
      controller.abort();
    };
  }, [field, value, typed]);

  return (
    <>
      <Input
        id={id}
        value={value}
        onChange={(e) => {
          setTyped(true);
          // 장비 글 칸은 모두 100자 (서버 EquipmentRules와 같음)
          onChange(clip(e, 100));
        }}
        list={listId}
        autoComplete="off"
        error={invalid}
        aria-invalid={invalid || undefined}
        aria-describedby={describedBy}
      />
      <datalist id={listId}>
        {(typed && value.trim() ? options : []).map((option) => (
          <option key={option} value={option} />
        ))}
      </datalist>
    </>
  );
}
