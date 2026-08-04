<template>
  <main class="login-page">
    <section class="login-shell" aria-labelledby="login-title">
      <div class="login-context">
        <div class="brand-row">
          <span class="brand-mark" aria-hidden="true">医</span>
          <div class="brand-copy">
            <h1 id="login-title">全医慧助（PCIE）</h1>
            <p>Primary Care Intelligent Expert</p>
          </div>
        </div>
        <div class="system-facts" aria-label="系统范围">
          <span>区域配置</span>
          <span>机构令牌</span>
          <span>安全审计</span>
        </div>
      </div>

      <div class="login-form-panel">
        <el-form
          ref="formRef"
          :model="form"
          :rules="rules"
          label-position="top"
          @submit.native.prevent="submitForm"
        >
          <h2 class="form-title">管理员登录</h2>
          <el-form-item label="账号" prop="username">
            <el-input
              v-model.trim="form.username"
              name="username"
              autocomplete="username"
              spellcheck="false"
              placeholder="输入管理员账号…"
              @keyup.enter.native="submitForm"
            />
          </el-form-item>
          <el-form-item label="密码" prop="password">
            <el-input
              v-model="form.password"
              name="password"
              type="password"
              show-password
              autocomplete="current-password"
              placeholder="输入登录密码…"
              @keyup.enter.native="submitForm"
            />
          </el-form-item>
          <el-button type="primary" class="submit-button" :loading="submitting" @click="submitForm">
            {{ submitting ? '登录中…' : '登录' }}
          </el-button>
        </el-form>
      </div>
    </section>
  </main>
</template>

<script>
import http from '../api/http'
import { setAdminAuth } from '../utils/auth'

function resolveRedirectPath(value) {
  return typeof value === 'string' && value.indexOf('/') === 0 ? value : '/overview'
}

export default {
  data() {
    return {
      submitting: false,
      form: {
        username: '',
        password: ''
      },
      rules: {
        username: [{ required: true, message: '请输入账号', trigger: 'blur' }],
        password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
      }
    }
  },
  methods: {
    submitForm() {
      this.$refs.formRef.validate(async valid => {
        if (!valid) {
          return
        }

        this.submitting = true
        try {
          const data = await http.post('/admin/api/auth/login', {
            username: this.form.username,
            password: this.form.password
          })

          if (!data || !data.token) {
            throw new Error('登录响应缺少 token')
          }

          setAdminAuth({
            token: data.token,
            expiresAt: data.expiresAt,
            user: data.user || null
          })

          this.$message.success('登录成功')
          this.$router.replace(resolveRedirectPath(this.$route.query.redirect))
        } catch (error) {
          this.$message.error((error && error.message) || '登录失败，请检查账号或密码')
        } finally {
          this.submitting = false
        }
      })
    }
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: grid;
  place-items: center;
  padding: 32px;
  background:
    linear-gradient(180deg, rgba(255, 255, 255, 0.72), rgba(255, 255, 255, 0)),
    #eef1f5;
}

.login-shell {
  width: min(920px, 100%);
  display: grid;
  grid-template-columns: minmax(0, 1fr) 420px;
  min-height: 468px;
  overflow: hidden;
  border: 1px solid var(--border-color-base);
  border-radius: 8px;
  background: #fff;
  box-shadow: 0 16px 42px rgba(27, 45, 55, 0.08);
}

.login-context {
  display: flex;
  flex-direction: column;
  justify-content: space-between;
  padding: 40px;
  background:
    linear-gradient(180deg, rgba(29, 158, 117, 0.08), rgba(39, 100, 165, 0.06)),
    #f9fcfb;
  border-right: 1px solid var(--border-color-base);
}

.brand-row {
  display: flex;
  align-items: center;
  gap: 14px;
  min-width: 0;
}

.brand-mark {
  width: 42px;
  height: 42px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex: 0 0 auto;
  border-radius: 8px;
  background: var(--color-primary);
  color: #fff;
  font-size: 17px;
  font-weight: 500;
}

.brand-copy {
  min-width: 0;
}

.brand-copy h1 {
  margin: 0;
  color: var(--color-text-primary);
  font-size: 22px;
  font-weight: 500;
  line-height: 1.3;
  text-wrap: balance;
}

.brand-copy p {
  margin: 4px 0 0;
  color: var(--color-text-secondary);
  font-family: var(--font-mono);
  font-size: 12px;
  overflow-wrap: anywhere;
}

.system-facts {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.system-facts span {
  display: inline-flex;
  align-items: center;
  min-height: 26px;
  padding: 0 10px;
  border: 1px solid var(--border-color-base);
  border-radius: 999px;
  background: #fff;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.login-form-panel {
  display: flex;
  align-items: center;
  padding: 40px;
}

.login-form-panel ::v-deep .el-form {
  width: 100%;
}

.login-form-panel ::v-deep .el-input__inner {
  height: 40px;
  line-height: 40px;
  padding: 0 14px;
}

.form-title {
  margin: 0 0 24px;
  color: var(--color-text-primary);
  font-size: 20px;
  font-weight: 500;
}

.submit-button {
  width: 100%;
  height: 40px;
  margin-top: 8px;
}

@media (max-width: 820px) {
  .login-page {
    padding: 16px;
  }

  .login-shell {
    grid-template-columns: 1fr;
  }

  .login-context {
    min-height: 180px;
    padding: 28px 24px;
    border-right: none;
    border-bottom: 1px solid var(--border-color-base);
  }

  .login-form-panel {
    padding: 28px 24px;
  }
}
</style>
