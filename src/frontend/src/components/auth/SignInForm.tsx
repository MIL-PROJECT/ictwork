import { ApiError } from "@/api/client";
import Label from "@/components/form/Label";
import Input from "@/components/form/input/InputField";
import Button from "@/components/ui/button/Button";
import { useAuth } from "@/context/AuthContext";
import { ArrowRightIcon, EyeCloseIcon, EyeIcon, LockIcon, UserIcon } from "@/icons";
import { type FormEvent, useState } from "react";
import { useTranslation } from "react-i18next";
import { useLocation, useNavigate } from "react-router";

/** 로그인 뒤 돌아갈 주소: 같은 사이트의 상대 경로만(`/`로 시작, `//`·`/\`는 아님). 로그인 화면 자신과 QA 계정 고르기는 뺀다 */
function returnPath(from: unknown): string | null {
  if (typeof from !== "string" || !from.startsWith("/") || from.startsWith("//") || from.startsWith("/\\")) return null;
  // 화면 경로는 대소문자를 가리지 않으므로 작은 글자로 견준다
  const path = from.split(/[?#]/)[0].replace(/\/+$/, "").toLowerCase();
  return path === "/signin" || path === "/qa" ? null : from;
}

// 아이디·비밀번호 로그인 (REQ-001). 원본의 소셜 로그인·로그인 유지·비밀번호 찾기·회원가입은 제거.
// 다른 화면에서 보내졌으면 로그인 뒤 그 화면으로 돌아가고, 쓰다가 세션이 끊겨 왔으면 그 안내를 보인다
export default function SignInForm() {
  const { t } = useTranslation("auth", { keyPrefix: "signIn" });
  const { t: tf } = useTranslation("frameAdmin", { keyPrefix: "login" });
  const { signIn } = useAuth();
  const navigate = useNavigate();
  const state = useLocation().state as { from?: unknown; expired?: unknown } | null;
  const returnTo = returnPath(state?.from);
  const expired = state?.expired === true;
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [showPassword, setShowPassword] = useState(false);
  // failed: 아이디·비밀번호가 틀림(입력 칸 표시), error: 그 밖의 오류
  const [error, setError] = useState<"failed" | "error" | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e: FormEvent<HTMLFormElement>) => {
    e.preventDefault();
    setSubmitting(true);
    setError(null);
    try {
      await signIn(loginId, password);
      navigate(returnTo ?? "/", { replace: true });
    } catch (err) {
      setError(err instanceof ApiError && err.status === 401 ? "failed" : "error");
    } finally {
      setSubmitting(false);
    }
  };

  // OMNIS 로그인 입력 칸: 높이 50, 모서리 14, 앞쪽 아이콘 자리
  const fieldClass = "h-[50px]! rounded-[14px]! bg-gray-50! ps-11! text-[14.5px]! dark:bg-gray-900!";
  const fieldIcon = "pointer-events-none absolute start-4 top-1/2 z-10 size-5 -translate-y-1/2 text-gray-400 group-focus-within:text-brand-500";

  return (
    <div className="flex flex-1 flex-col">
      {/* OMNIS 로그인 폼 (.login-form): 상징 타일 → 큰 제목 → 안내 → 입력 → 넓은 단추 → 접속 안내 */}
      <div className="mx-auto flex w-full max-w-[380px] flex-1 flex-col justify-center">
        <div>
          <div className="mb-6">
            <span aria-hidden className="mb-3.5 grid h-[54px] w-[62px] place-items-center rounded-2xl bg-brand-100 dark:bg-white/10">
              <img width={34} height={34} src="/images/logo/logo-icon.svg" alt="" />
            </span>
            <h1 className="text-[28px] leading-tight font-extrabold tracking-[-0.8px] text-gray-900 dark:text-white/90">
              {t("title")}
            </h1>
            <p className="mt-2 text-sm text-gray-500 dark:text-gray-400">
              {t("description")}
            </p>
            {expired && (
              <p role="status" className="mt-4 rounded-xl bg-warning-50 px-3.5 py-2.5 text-sm text-warning-700 ring-1 ring-warning-500/25 ring-inset dark:bg-warning-500/15 dark:text-warning-400">
                {t("expired")}
              </p>
            )}
          </div>
          <form onSubmit={handleSubmit}>
            <div className="space-y-[18px]">
              <div>
                <Label htmlFor="loginId" className="mb-[7px] ms-0.5 text-[12.5px] font-semibold text-gray-700">
                  {t("loginId")} <span className="text-error-600 dark:text-error-400">*</span>{" "}
                </Label>
                <div className="group relative">
                  <Input
                    id="loginId"
                    name="loginId"
                    autoComplete="username"
                    value={loginId}
                    onChange={(e) => setLoginId(e.target.value)}
                    aria-invalid={error === "failed"}
                    aria-describedby={error ? "signin-error" : undefined}
                    className={fieldClass}
                  />
                  <UserIcon aria-hidden="true" className={fieldIcon} />
                </div>
              </div>
              <div>
                <Label htmlFor="password" className="mb-[7px] ms-0.5 text-[12.5px] font-semibold text-gray-700">
                  {t("password")} <span className="text-error-600 dark:text-error-400">*</span>{" "}
                </Label>
                <div className="group relative">
                  <Input
                    id="password"
                    name="password"
                    type={showPassword ? "text" : "password"}
                    autoComplete="current-password"
                    value={password}
                    onChange={(e) => setPassword(e.target.value)}
                    aria-invalid={error === "failed"}
                    aria-describedby={error ? "signin-error" : undefined}
                    className={fieldClass}
                  />
                  <LockIcon aria-hidden="true" className={fieldIcon} />
                  {/* 비밀번호 보기: 키보드로도 누를 수 있는 버튼, 켜짐 상태를 알린다 */}
                  <button
                    type="button"
                    onClick={() => setShowPassword(!showPassword)}
                    aria-label={t("showPassword")}
                    aria-pressed={showPassword}
                    className="absolute inset-e-4 top-1/2 z-30 -translate-y-1/2 cursor-pointer rounded"
                  >
                    {showPassword ? (
                      <EyeIcon className="size-5 fill-gray-500 dark:fill-gray-400" />
                    ) : (
                      <EyeCloseIcon className="size-5 fill-gray-500 dark:fill-gray-400" />
                    )}
                  </button>
                </div>
              </div>
              {error && (
                <p id="signin-error" role="alert" className="rounded-xl bg-error-50 px-3.5 py-2.5 text-sm text-error-600 ring-1 ring-error-500/20 ring-inset dark:bg-error-500/10 dark:text-error-400">
                  {t(error)}
                </p>
              )}
              <div className="pt-1.5">
                <Button className="h-[52px] w-full rounded-[14px]! text-[15px]!" size="md" disabled={submitting} endIcon={<ArrowRightIcon aria-hidden="true" className="size-5 fill-current" />}>
                  {t("submit")}
                </Button>
              </div>
            </div>
          </form>
          <p className="mt-[22px] flex gap-2 border-t border-gray-100 pt-5 text-[11.5px] leading-normal text-gray-400 dark:border-gray-800">
            <LockIcon aria-hidden="true" className="mt-px size-[15px] shrink-0" />
            {tf("legal")}
          </p>
        </div>
      </div>
    </div>
  );
}
