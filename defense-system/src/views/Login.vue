<template>
  <main class="login-page">
    <section class="login-stage" aria-label="AI 面试评估系统登录">
      <div class="login-hero">
        <h1>智能面试考核系统</h1>
      </div>

      <aside class="login-panel" aria-label="账号登录">
        <div class="panel-top">
          <div class="panel-tabs" aria-label="登录方式">
            <span class="active">账号登录</span>
          </div>
          <div class="panel-badge">
            <SafetyCertificateOutlined />
            <span>安全访问</span>
          </div>
        </div>

        <div class="panel-heading">
          <h2>欢迎登录</h2>
          <p>输入账号密码，进入对应的面试评估工作台</p>
        </div>

        <Form
          ref="formRef"
          :model="data"
          :rules="rules"
          layout="vertical"
          size="large"
          class="login-form"
          @finish="submitLogin"
        >
          <FormItem name="account">
            <Input
              v-model:value.trim="data.account"
              autocomplete="username"
              placeholder="请输入账号"
            >
              <template #prefix>
                <UserOutlined />
              </template>
            </Input>
          </FormItem>
          <FormItem name="password">
            <InputPassword
              v-model:value.trim="data.password"
              autocomplete="current-password"
              placeholder="请输入密码"
            >
              <template #prefix>
                <LockOutlined />
              </template>
            </InputPassword>
          </FormItem>
          <FormItem class="login-action">
            <Button
              html-type="submit"
              type="primary"
              :loading="loading"
              :disabled="!data.account || !data.password"
              @click="submitLogin"
              block
            >
              <span>立即登录</span>
              <ArrowRightOutlined />
            </Button>
          </FormItem>
        </Form>

        <div class="login-footer">
          <CheckCircleOutlined />
          <span>仅供授权用户访问，所有评估操作将被记录。</span>
        </div>
      </aside>
    </section>
  </main>
</template>

<script setup lang="ts">
import {
  Form,
  FormItem,
  Input,
  InputPassword,
  Button,
  type FormProps,
} from "ant-design-vue";
import {
  ArrowRightOutlined,
  CheckCircleOutlined,
  LockOutlined,
  SafetyCertificateOutlined,
  UserOutlined,
} from "@ant-design/icons-vue";
import { ref } from "vue";
import { useRoute, useRouter } from "vue-router";
import { useAuthStore } from "@/stores/auth";

const loading = ref(false);
const data = ref({
  account: "",
  password: "",
});
const rules: FormProps["rules"] = {
  account: [{ required: true, message: "请输入账号", trigger: "blur" }],
  password: [{ required: true, message: "请输入密码", trigger: "blur" }],
};
const formRef = ref();
const route = useRoute();
const router = useRouter();

const submitLogin = () => {
  if (!data.value.account || !data.value.password || loading.value) {
    return;
  }
  loading.value = true;
  useAuthStore()
    ?.login(data.value)
    .then(() => {
      router.replace((route.query.redirect as string) || "/");
    })
    .catch(() => {
      // Request interceptor owns the visible error notification.
    })
    .finally(() => {
      loading.value = false;
    });
};
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  width: 100%;
  display: flex;
  align-items: stretch;
  justify-content: center;
  padding: 0;
  background:
    linear-gradient(90deg, rgba(244, 250, 255, 0.8) 0%, rgba(244, 250, 255, 0.5) 36%, rgba(244, 250, 255, 0.18) 100%),
    linear-gradient(180deg, rgba(255, 255, 255, 0.28), rgba(236, 247, 255, 0.24)),
    url("@/assets/login/ai-interview-hero-clean.jpg") center center / cover no-repeat;
  color: #0f1f3d;
  overflow-x: hidden;
}

.login-page::before {
  position: fixed;
  inset: 0;
  z-index: 0;
  pointer-events: none;
  content: "";
  background:
    radial-gradient(circle at 12% 18%, rgba(255, 255, 255, 0.72), transparent 24%),
    radial-gradient(circle at 84% 18%, rgba(255, 255, 255, 0.36), transparent 22%);
}

.login-stage {
  position: relative;
  z-index: 1;
  width: min(1180px, calc(100% - 80px));
  min-height: 100vh;
  display: grid;
  grid-template-columns: minmax(0, 1fr) 382px;
  align-items: start;
  gap: 64px;
}

.login-hero {
  position: relative;
  display: flex;
  align-items: flex-start;
  min-height: 100vh;
  padding-top: clamp(112px, 15vh, 170px);
}

.login-hero h1 {
  margin: 0;
  max-width: 540px;
  color: #071a39;
  font-size: clamp(42px, 4.4vw, 64px);
  line-height: 1.08;
  font-weight: 800;
  letter-spacing: 0;
  text-shadow: 0 10px 30px rgba(255, 255, 255, 0.92);
}

.login-panel {
  position: relative;
  z-index: 4;
  width: 100%;
  min-height: 426px;
  margin-top: clamp(92px, 14vh, 150px);
  padding: 30px 30px 28px;
  display: flex;
  flex-direction: column;
  justify-content: flex-start;
  border: 1px solid rgba(193, 218, 250, 0.78);
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.9);
  box-shadow:
    0 28px 76px rgba(42, 111, 198, 0.13),
    0 8px 24px rgba(54, 103, 164, 0.06),
    inset 0 1px 0 rgba(255, 255, 255, 0.96);
  backdrop-filter: blur(20px);
}

.panel-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  padding-bottom: 20px;
  border-bottom: 1px solid rgba(211, 226, 247, 0.78);
}

.panel-tabs {
  display: inline-flex;
  align-items: center;
  color: #647899;
  font-size: 14px;
  font-weight: 800;
}

.panel-tabs span {
  position: relative;
  padding: 0 2px 10px;
}

.panel-tabs span::after {
  position: absolute;
  right: 2px;
  bottom: 0;
  left: 2px;
  height: 3px;
  border-radius: 999px;
  background: linear-gradient(90deg, #0d7bff, #21b7ff);
  content: "";
}

.panel-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  color: #0d7bff;
  font-size: 12px;
  font-weight: 800;
}

.panel-heading {
  padding: 28px 0 22px;
}

.panel-heading h2 {
  margin: 0;
  color: #081a39;
  font-size: 26px;
  line-height: 1.2;
  font-weight: 800;
}

.panel-heading p {
  margin: 8px 0 0;
  color: #6a7d9f;
  font-size: 14px;
}

.login-form {
  width: 100%;
}

.login-form :deep(.ant-form-item) {
  margin-bottom: 18px;
}

.login-form :deep(.ant-input-affix-wrapper) {
  height: 44px;
  padding: 0 14px;
  border-color: rgba(202, 222, 247, 0.9) !important;
  border-radius: 4px !important;
  background: rgba(255, 255, 255, 0.82) !important;
  box-shadow: none;
}

.login-form :deep(.ant-input-affix-wrapper:hover),
.login-form :deep(.ant-input-affix-wrapper-focused) {
  border-color: rgba(13, 123, 255, 0.54) !important;
  box-shadow:
    0 0 0 3px rgba(13, 123, 255, 0.1),
    0 10px 20px rgba(13, 123, 255, 0.08) !important;
}

.login-form :deep(.ant-input) {
  background: transparent;
  color: #0f1f3d;
  font-size: 14px;
}

.login-form :deep(.ant-input-prefix) {
  margin-right: 10px;
  color: #8ba0bf;
}

.login-action {
  margin-top: 6px;
  margin-bottom: 0 !important;
}

.login-action :deep(.ant-btn) {
  height: 46px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  border: none;
  border-radius: 5px;
  background: linear-gradient(90deg, #0d7bff, #1bb7f5);
  color: #ffffff;
  font-size: 15px;
  font-weight: 800;
  box-shadow: 0 16px 28px rgba(13, 123, 255, 0.24);
}

.login-action :deep(.ant-btn:not(:disabled):hover) {
  background: linear-gradient(90deg, #086ce2, #0da8ee);
  color: #ffffff;
}

.login-action :deep(.ant-btn:disabled) {
  background: linear-gradient(90deg, rgba(13, 123, 255, 0.58), rgba(27, 183, 245, 0.58));
  color: #ffffff;
  box-shadow: 0 12px 24px rgba(13, 123, 255, 0.14);
}

.login-footer {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 7px;
  margin-top: 24px;
  color: #6f82a1;
  font-size: 12px;
  line-height: 1.6;
  text-align: center;
}

.login-footer :deep(.anticon) {
  color: #0d7bff;
}

@media (max-width: 960px) {
  .login-page {
    min-height: 100dvh;
    padding: 24px;
    align-items: flex-start;
    overflow-y: auto;
    background-position: 48% center;
  }

  .login-stage {
    width: 100%;
    min-height: calc(100dvh - 48px);
    grid-template-columns: 1fr;
    gap: 24px;
  }

  .login-hero {
    min-height: auto;
    padding-top: 44px;
  }

  .login-panel {
    width: min(420px, 100%);
    margin: 0 auto 20px;
  }
}

@media (max-width: 520px) {
  .login-page {
    padding: 18px;
    background-position: center center;
  }

  .login-stage {
    min-height: calc(100dvh - 36px);
  }

  .login-hero {
    padding-top: 26px;
  }

  .login-hero h1 {
    max-width: 300px;
    font-size: 34px;
  }

  .login-panel {
    padding: 24px 18px 22px;
  }
}
</style>
