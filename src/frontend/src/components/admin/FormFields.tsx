import Label from "@/components/form/Label";
import Input from "@/components/form/input/InputField";
import { clip } from "@/utils";

// 관리 화면 입력 칸: 이름표가 붙은 입력·선택 상자 (값은 부모가 관리)
const SELECT_CLASS =
  "h-11 w-full appearance-none rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 pe-11 text-sm text-gray-800 shadow-theme-xs focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:focus:border-brand-800";

export function TextField({
  id,
  label,
  value,
  onChange,
  max,
  required,
}: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  max?: number; // 글자 수 한도(서버와 같은 값, 코드 포인트)
  required?: boolean; // 비우면 저장 단추가 꺼지는 칸: 이름표에 * (C-4)
}) {
  return (
    <div>
      <Label htmlFor={id} required={required}>
        {label}
      </Label>
      <Input id={id} value={value} onChange={(e) => onChange(max === undefined ? e.target.value : clip(e, max))} />
    </div>
  );
}

export function SelectField({
  id,
  label,
  value,
  options,
  placeholder,
  onChange,
  required,
}: {
  id: string;
  label: string;
  value: string;
  options: { value: string; label: string }[];
  placeholder: string;
  onChange: (value: string) => void;
  required?: boolean; // 비우면 저장 단추가 꺼지는 칸: 이름표에 * (C-4)
}) {
  return (
    <div>
      <Label htmlFor={id} required={required}>
        {label}
      </Label>
      <select id={id} className={SELECT_CLASS} value={value} onChange={(e) => onChange(e.target.value)}>
        <option value="" disabled>
          {placeholder}
        </option>
        {options.map((option) => (
          <option key={option.value} value={option.value}>
            {option.label}
          </option>
        ))}
      </select>
    </div>
  );
}
