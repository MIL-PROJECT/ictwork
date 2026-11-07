import ThemeTogglerTwo from "@/components/common/ThemeTogglerTwo";
import { AiIcon, PieChartIcon, TaskIcon } from "@/icons";
import React from "react";
import { useTranslation } from "react-i18next";
import { Link } from "react-router";

// OMNIS 로그인 (style.css .login): 넓은 화면은 왼쪽 짙은 남색 소개(7) + 오른쪽 흰 입력(3, 최소 440px). 좁은 화면은 입력만
export default function AuthLayout({
  children,
}: {
  children: React.ReactNode;
}) {
  const { t } = useTranslation();
  const { t: tf } = useTranslation("frameAdmin", { keyPrefix: "login" });
  // 소개 카드: 체계에 실제로 있는 기능만 (업무·결재, 정보통신 현황, AI 질의응답)
  const points = [
    { key: "work", icon: <TaskIcon /> },
    { key: "status", icon: <PieChartIcon /> },
    { key: "ai", icon: <AiIcon /> },
  ];
  return (
    <div className="relative z-1 bg-white dark:bg-gray-900">
      <div className="relative grid min-h-screen w-full lg:grid-cols-[minmax(0,7fr)_minmax(440px,3fr)]">
        <div className="relative hidden flex-col justify-center gap-10 overflow-hidden bg-linear-155 from-brand-500 via-brand-700 to-brand-900 px-[clamp(40px,6vw,88px)] py-14 text-white lg:flex">
          {/* 장식: 옅은 빛 + 가장자리로 사라지는 가는 격자 + 레이더 동심원·회전 스윕 */}
          <div aria-hidden className="pointer-events-none absolute inset-0 bg-[radial-gradient(60%_50%_at_12%_8%,rgba(110,128,255,0.3),transparent_70%),radial-gradient(45%_40%_at_95%_100%,rgba(125,110,255,0.18),transparent_70%)]" />
          <div
            aria-hidden
            className="pointer-events-none absolute inset-0 bg-[linear-gradient(rgba(255,255,255,0.045)_1px,transparent_1px),linear-gradient(90deg,rgba(255,255,255,0.045)_1px,transparent_1px)] bg-size-[48px_48px] [mask-image:radial-gradient(70%_60%_at_30%_40%,#000_20%,transparent_80%)]"
          />
          <svg aria-hidden viewBox="0 0 600 600" className="pointer-events-none absolute -end-[170px] -bottom-[190px] size-[620px] fill-none stroke-[rgba(170,182,255,0.14)]">
            <defs>
              <linearGradient id="login-radar-sweep" x1="0" y1="0" x2="1" y2="0">
                <stop offset="0" stopColor="#8fa0ff" stopOpacity="0" />
                <stop offset="1" stopColor="#8fa0ff" stopOpacity="0.35" />
              </linearGradient>
            </defs>
            {[80, 150, 220, 290].map((r) => (
              <circle key={r} cx="300" cy="300" r={r} />
            ))}
            <path d="M300 300 10 300M300 10 300 590" />
            {/* 레이더 스윕: 9초에 한 바퀴 (OMNIS .login-radar-sweep, 움직임 줄이기 설정이면 멈춤) */}
            <path className="login-radar-sweep" d="M300 300 L590 300 A290 290 0 0 0 505 95 Z" fill="url(#login-radar-sweep)" stroke="none" />
          </svg>

          <Link to="/" className="absolute top-11 left-[clamp(40px,6vw,88px)] flex items-center gap-4">
            <span className="grid h-[52px] w-[60px] place-items-center rounded-[15px] bg-white/10 shadow-[inset_0_0_0_1px_rgba(255,255,255,0.14)] backdrop-blur-sm">
              <img width={36} height={36} src="/images/logo/logo-icon.svg" alt="" />
            </span>
            <span className="text-2xl font-extrabold tracking-[-0.02em] whitespace-nowrap text-white">{t("common.appName")}</span>
          </Link>

          <div className="relative max-w-[540px]">
            <span className="inline-flex items-center gap-2.5 text-[11.5px] font-semibold tracking-[0.14em] text-[#9fb0ff] uppercase before:h-0.5 before:w-6 before:rounded-sm before:bg-linear-90 before:from-[#6f82ff] before:to-[#c9d2ff]">
              {tf("eyebrow")}
            </span>
            <p className="mt-5 mb-[18px] text-[clamp(32px,3.2vw,46px)] leading-[1.26] tracking-[-0.035em]">
              <span className="font-extralight text-white/90">{tf("headlineThin")}</span>
              <br />
              <span className="bg-linear-90 from-white to-[#c9d2ff] bg-clip-text font-bold text-transparent">{tf("headlineStrong")}</span>
            </p>
            <p className="max-w-[460px] text-[14.5px] leading-[1.7] text-white/70">{tf("lead")}</p>
          </div>

          <ul className="relative grid max-w-[640px] grid-cols-1 gap-3 xl:grid-cols-3">
            {points.map((p) => (
              <li key={p.key} className="flex flex-col gap-3 rounded-2xl bg-white/5 px-4 pt-4 pb-[18px] shadow-[inset_0_0_0_1px_rgba(255,255,255,0.08)] backdrop-blur-md">
                <span aria-hidden className="grid size-[34px] place-items-center rounded-[10px] bg-[rgba(143,160,255,0.16)] text-[#c9d1ff] [&_svg]:size-[18px]">
                  {p.icon}
                </span>
                <span>
                  <strong className="block text-[13.5px] font-bold text-white">{tf(`points.${p.key}.title`)}</strong>
                  <small className="mt-0.5 block text-xs leading-normal text-white/60">{tf(`points.${p.key}.desc`)}</small>
                </span>
              </li>
            ))}
          </ul>
        </div>

        <div className="flex flex-col justify-center bg-white px-6 py-12 sm:px-8 lg:shadow-[-1px_0_0_var(--color-gray-100)] dark:bg-gray-900">
          {children}
        </div>
      </div>

      <div className="fixed inset-e-6 bottom-6 z-50 hidden sm:block">
        <ThemeTogglerTwo />
      </div>
    </div>
  );
}
