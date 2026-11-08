import { fetchMe, login, logout, type FontSize, type Me } from "@/api/auth";
import { returnToQaPicker } from "@/api/qa";
import { ApiError, setForbiddenListener, setUnauthorizedListener } from "@/api/client";
import type React from "react";
import { createContext, useContext, useEffect, useRef, useState } from "react";

// 로그인하지 않은 상태가 된 까닭: 쓰다가 세션이 끊김(401), 로그아웃 단추. 처음부터 로그인하지 않았으면 null (REQ-001)
export type SignedOutReason = "expired" | "signedOut" | null;

// 로그인 사용자 정보. undefined는 확인 중, null은 로그인 안 함
type AuthContextType = {
  user: Me | null | undefined;
  signedOutReason: SignedOutReason;
  signIn: (loginId: string, password: string) => Promise<void>;
  signOut: () => Promise<boolean>; // 로그아웃되면(QA로 고른 계정이면 계정 고르기로 돌아가면) true
  refresh: () => Promise<void>; // 현재 사용자(역할·강제 변경 여부)를 다시 읽음
  setFontSize: (fontSize: FontSize) => void; // 글자 크기를 화면에 바로 반영(저장은 부르는 쪽이 한다, REQ-008)
  setShowGuides: (showGuides: boolean) => void; // 설명 글 보이기를 화면에 바로 반영(저장은 부르는 쪽이 한다, REQ-009)
};

const AuthContext = createContext<AuthContextType | undefined>(undefined);

export const AuthProvider: React.FC<{ children: React.ReactNode }> = ({
  children,
}) => {
  const [user, setUser] = useState<Me | null | undefined>(undefined);
  const [signedOutReason, setSignedOutReason] = useState<SignedOutReason>(null);
  // 401을 받은 때 로그인한 채였는가(세션 끊김 안내), 로그아웃 중인가(안내 없음)를 듣는 함수에서 본다
  const userRef = useRef<Me | null | undefined>(undefined);
  const signingOut = useRef(false);
  useEffect(() => {
    userRef.current = user;
  }, [user]);

  // 개인설정 글자 크기 (REQ-008): 크게면 <html data-font-size="large">로 화면 전체 125%(index.css). 로그인 전·로그아웃 뒤는 보통
  const fontSize = user?.fontSize;
  useEffect(() => {
    applyFontSize(fontSize);
  }, [fontSize]);

  // 개인설정 설명 글 보이기 (REQ-009): 끄면 <html data-guides="off">로 설명 글(data-guide)을 숨긴다(index.css). 로그인 전·로그아웃 뒤는 보임
  const guidesOff = user?.showGuides === false;
  useEffect(() => {
    if (guidesOff) document.documentElement.dataset.guides = "off";
    else delete document.documentElement.dataset.guides;
  }, [guidesOff]);

  useEffect(() => {
    setUnauthorizedListener(() => {
      // 로그아웃 중이면 까닭을 같은 때에 넣어, 그 사이 그려져도 돌아갈 주소·끊김 안내가 담기지 않게 한다
      if (signingOut.current) setSignedOutReason("signedOut");
      else if (userRef.current) setSignedOutReason("expired");
      setUser(null);
    });
    // 403이면 역할이 바뀌었거나 비밀번호를 바꿔야 할 수 있어 현재 사용자를 다시 읽는다 (02 6장, DEC-056)
    setForbiddenListener(() => {
      fetchMe()
        .then(setUser)
        .catch(() => undefined);
    });
    // 앱 시작 때 현재 사용자 확인. 로그인 전이면 401이고, 이때 CSRF 쿠키를 받는다
    fetchMe()
      .then(setUser)
      .catch(() => setUser(null));
    return () => {
      setUnauthorizedListener(null);
      setForbiddenListener(null);
    };
  }, []);

  const refresh = async () => {
    setUser(await fetchMe());
  };

  const setFontSize = (fontSize: FontSize) => {
    setUser((prev) => (prev ? { ...prev, fontSize } : prev));
  };

  const setShowGuides = (showGuides: boolean) => {
    setUser((prev) => (prev ? { ...prev, showGuides } : prev));
  };

  const signIn = async (loginId: string, password: string) => {
    setUser(await login(loginId, password));
    setSignedOutReason(null);
  };

  const signedOut = () => {
    setSignedOutReason("signedOut");
    setUser(null);
  };

  // 로그아웃 뒤 /api/me로 서버 세션이 끝났는지 확인한다. 401이면 로그아웃(이때 다음 로그인용 새 CSRF 토큰 쿠키도 받는다).
  // 세션이 남았거나 서버에 닿지 않으면 로그인 상태를 유지하고 false를 돌려준다(공용 PC에서 로그아웃된 것처럼 보이지 않게)
  // QA 마스터가 고른 계정이면 로그아웃 대신 계정 고르기로 돌아간다 (DEC-067). 고르기 화면의 "QA 끝내기"가 실제 로그아웃
  const signOut = async () => {
    signingOut.current = true;
    try {
      if (user?.qaMode === "SWITCHED") {
        try {
          setUser(await returnToQaPicker());
          return true;
        } catch (err) {
          if (err instanceof ApiError && err.status === 401) {
            signedOut();
            return true;
          }
          return false;
        }
      }
      try {
        await logout();
      } catch {
        // 아래에서 세션이 남았는지 확인한다
      }
      try {
        setUser(await fetchMe());
        return false;
      } catch (err) {
        if (err instanceof ApiError && err.status === 401) {
          signedOut();
          return true;
        }
        return false;
      }
    } finally {
      signingOut.current = false;
    }
  };

  return (
    <AuthContext.Provider value={{ user, signedOutReason, signIn, signOut, refresh, setFontSize, setShowGuides }}>
      {children}
    </AuthContext.Provider>
  );
};

export const useAuth = () => {
  const context = useContext(AuthContext);
  if (context === undefined) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
};

// 글자 크기 "크게"의 화면 배율. index.css의 html[data-font-size="large"] { zoom: 1.25 }와 같은 값 (REQ-008, slices/18)
const LARGE_ZOOM = 1.25;

// 화면에 적용한다. 보통은 위 효과가 부르고, 업무 현황 인쇄 동안 잠시 보통으로 둘 때도 쓴다 (WorkStatus)
export function applyFontSize(fontSize: FontSize | undefined) {
  if (fontSize === "LARGE") document.documentElement.dataset.fontSize = "large";
  else delete document.documentElement.dataset.fontSize;
}

// 설명 글을 보이는가 (REQ-009). 설명만 하는 툴팁(title)은 CSS로 숨길 수 없어 이 값으로 주고 뺀다. 로그인 전은 보임
export function useGuides() {
  const { user } = useAuth();
  return user?.showGuides !== false;
}

// 지금 화면 배율(보통 1, 크게 1.25). 외부 부품을 배율 밖에서 그릴 때 글자·높이를 이만큼 키우고,
// 화면 좌표(getBoundingClientRect)로 CSS 위치를 정할 때 이 값으로 나눈다. 인쇄할 때는 index.css가 배율을 1로 둔다
export function useAppZoom() {
  const { user } = useAuth();
  return user?.fontSize === "LARGE" ? LARGE_ZOOM : 1;
}
