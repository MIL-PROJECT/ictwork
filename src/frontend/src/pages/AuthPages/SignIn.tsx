import SignInForm from "@/components/auth/SignInForm";
import PageMeta from "@/components/common/PageMeta";
import AuthLayout from "./AuthPageLayout";

export default function SignIn() {
  return (
    <>
      <PageMeta titleKey="sidebar.items.signIn" />
      <AuthLayout>
        <SignInForm />
      </AuthLayout>
    </>
  );
}
