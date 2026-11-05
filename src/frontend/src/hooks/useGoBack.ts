import { useNavigate } from "react-router";

// fallback: 이 앱 안에서 앞 화면이 없을 때(주소를 바로 연 경우) 갈 곳
const useGoBack = (fallback = "/") => {
  const navigate = useNavigate();

  const goBack = () => {
    if (window.history.state && window.history.state.idx > 0) {
      navigate(-1); // Go back to the previous page
    } else {
      navigate(fallback); // Redirect to the fallback (home by default) if no history exists
    }
  };

  return goBack;
};

export default useGoBack;
