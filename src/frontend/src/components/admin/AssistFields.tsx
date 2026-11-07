import AssistLabel from "@/components/assist/AssistLabel";
import type { AssistHandle } from "@/components/assist/useAssistAdapter";
import { RequiredMark } from "@/components/form/Label";
import Input from "@/components/form/input/InputField";
import { clip } from "@/utils";

// 관리 화면 입력 칸(FormFields의 TextField·SelectField와 같은 모양)에 AI 업무지원 칸 이름 표시를 더한 것 (REQ-C03 ④⑤, slices/21 12.5):
// 이름표 옆 "AI가 채움"과 출처. assist가 없으면 FormFields와 같다
const LABEL_CLASS = "mb-1.5 block text-sm font-medium text-gray-700 dark:text-gray-400";
const SELECT_CLASS =
  "h-11 w-full appearance-none rounded-lg border border-gray-300 bg-transparent px-4 py-2.5 pe-11 text-sm text-gray-800 shadow-theme-xs focus:border-brand-300 focus:ring-3 focus:ring-brand-500/10 focus:outline-hidden dark:border-gray-700 dark:bg-gray-900 dark:text-white/90 dark:focus:border-brand-800";

export function AssistTextField({ id, label, value, onChange, max, required, assist, field }: {
  id: string;
  label: string;
  value: string;
  onChange: (value: string) => void;
  max?: number;
  required?: boolean;
  assist: AssistHandle | null;
  field: string; // 업무지원 칸 id
}) {
  return (
    <div>
      <AssistLabel assist={assist} field={field} htmlFor={id} className={LABEL_CLASS}>
        {label}
        {required && <RequiredMark />}
      </AssistLabel>
      <Input id={id} value={value} onChange={(e) => onChange(max === undefined ? e.target.value : clip(e, max))} />
    </div>
  );
}

export function AssistSelectField({ id, label, value, options, placeholder, onChange, required, assist, field }: {
  id: string;
  label: string;
  value: string;
  options: { value: string; label: string }[];
  placeholder: string;
  onChange: (value: string) => void;
  required?: boolean;
  assist: AssistHandle | null;
  field: string;
}) {
  return (
    <div>
      <AssistLabel assist={assist} field={field} htmlFor={id} className={LABEL_CLASS}>
        {label}
        {required && <RequiredMark />}
      </AssistLabel>
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
