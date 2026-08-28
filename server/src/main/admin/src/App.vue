<template>
  <div class="app-root">
    <router-view v-if="isLoginPage" />
    <admin-layout
      v-else
      :menu-items="visibleMenuItems"
      :route-title="$route.meta.title || '管理端'"
      :visited-tags="visitedTags"
      :current-user-name="currentUserName"
      :current-user-initial="currentUserInitial"
      :can-change-password="canChangePassword"
      @open-password-dialog="openPasswordDialog"
      @logout="handleLogout"
      @close-tag="closeTag"
    >
      <router-view />
    </admin-layout>

    <el-dialog
      v-if="passwordDialogVisible"
      title="修改密码"
      :visible.sync="passwordDialogVisible"
      width="420px"
      @closed="resetPasswordForm"
    >
      <el-form
        ref="passwordFormRef"
        :model="passwordForm"
        :rules="passwordRules"
        label-position="top"
        @submit.native.prevent="submitPasswordForm"
      >
        <el-form-item label="当前密码" prop="oldPassword">
          <el-input
            v-model="passwordForm.oldPassword"
            type="password"
            show-password
            autocomplete="current-password"
            placeholder="请输入当前密码…"
            @keyup.enter.native="submitPasswordForm"
          />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input
            v-model="passwordForm.newPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="请输入至少 6 位的新密码…"
            @keyup.enter.native="submitPasswordForm"
          />
        </el-form-item>
        <el-form-item label="确认新密码" prop="confirmPassword">
          <el-input
            v-model="passwordForm.confirmPassword"
            type="password"
            show-password
            autocomplete="new-password"
            placeholder="请再次输入新密码…"
            @keyup.enter.native="submitPasswordForm"
          />
        </el-form-item>
      </el-form>
      <span slot="footer" class="dialog-footer">
        <el-button @click="passwordDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="passwordSubmitting" @click="submitPasswordForm">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from './api/http'
import AdminLayout from './components/layout/AdminLayout.vue'
import {
  AUTH_CHANGE_EVENT,
  clearAdminAuth,
  getAdminToken,
  getAdminUser,
  setAdminUser
} from './utils/auth'
import { isStatisticsOnlyUser } from './utils/access'

const menuItems = [
  { path: '/overview', label: '概览', icon: 'el-icon-s-home' },
  { path: '/users', label: '用户', icon: 'el-icon-user' },
  { path: '/ai-user-permissions', label: 'AI 使用权限', icon: 'el-icon-circle-check' },
  { path: '/roles', label: '角色', icon: 'el-icon-s-custom' },
  { path: '/regions', label: '区域', icon: 'el-icon-location' },
  { path: '/orgs', label: '机构', icon: 'el-icon-office-building' },
  { path: '/devices', label: '令牌', icon: 'el-icon-key' },
  { path: '/client-usage', label: '客户端使用情况', icon: 'el-icon-monitor' },
  { path: '/configs', label: '配置', icon: 'el-icon-setting' },
  { path: '/prompts', label: '提示词', icon: 'el-icon-edit-outline' },
  { path: '/symptom-templates', label: '症状模板', icon: 'el-icon-document-checked' },
  { path: '/inpatient-emr-templates', label: '病历模板', icon: 'el-icon-document-copy' },
  { path: '/exam-result-entry', label: '检验检查回写', icon: 'el-icon-edit-outline' },
  { path: '/releases', label: '版本发布', icon: 'el-icon-upload' },
  { path: '/logs', label: '日志', icon: 'el-icon-document' },
  { path: '/user-logs', label: '用户日志', icon: 'el-icon-notebook-2' },
  { path: '/business-workflow-debug', label: '业务调试', icon: 'el-icon-connection' },
  { path: '/feedbacks', label: '反馈', icon: 'el-icon-chat-dot-square' },
  { path: '/recommendation-preferences', label: '推荐偏好', icon: 'el-icon-sort' },
  { path: '/patient-memories', label: '患者记忆', icon: 'el-icon-collection' },
  { path: '/analytics', label: '统计分析', icon: 'el-icon-data-analysis' },
  { path: '/function-usage', label: '辅诊功能', icon: 'el-icon-s-data' },
  { path: '/user-activity', label: '用户活跃度', icon: 'el-icon-data-line' },
  { path: '/security-rejections', label: '安全拦截', icon: 'el-icon-lock' },
  { path: '/security-analytics', label: '安全分析', icon: 'el-icon-warning-outline' }
]

export default {
  components: {
    AdminLayout
  },
  data() {
    return {
      menuItems,
      hasToken: false,
      currentUser: null,
      syncingCurrentUser: false,
      visitedTags: [],
      passwordDialogVisible: false,
      passwordSubmitting: false,
      passwordForm: {
        oldPassword: '',
        newPassword: '',
        confirmPassword: ''
      },
      passwordRules: {
        oldPassword: [{ required: true, message: '请输入当前密码', trigger: 'blur' }],
        newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }],
        confirmPassword: [{ required: true, message: '请再次输入新密码', trigger: 'blur' }]
      }
    }
  },
  computed: {
    isLoginPage() {
      return this.$route.path === '/login'
    },
    currentUserName() {
      if (!this.currentUser) {
        return '管理员'
      }
      return this.currentUser.naUser || this.currentUser.cdUser || '管理员'
    },
    currentUserInitial() {
      return this.currentUserName.slice(0, 1)
    },
    canChangePassword() {
      return !this.currentUser || this.currentUser.authProvider !== 'BBP'
    },
    statisticsOnly() {
      return isStatisticsOnlyUser(this.currentUser)
    },
    landingPath() {
      return this.statisticsOnly ? '/analytics' : '/overview'
    },
    visibleMenuItems() {
      if (this.statisticsOnly) {
        return this.menuItems.filter(item => ['/analytics', '/function-usage', '/user-activity'].includes(item.path))
      }
      if (this.currentUser && this.currentUser.authProvider === 'BBP') {
        return this.menuItems
      }
      return this.menuItems.filter(item => item.path !== '/ai-user-permissions')
    }
  },
  created() {
    this.syncAuthState()
    if (typeof window !== 'undefined') {
      window.addEventListener(AUTH_CHANGE_EVENT, this.syncAuthState)
    }
  },
  beforeDestroy() {
    if (typeof window !== 'undefined') {
      window.removeEventListener(AUTH_CHANGE_EVENT, this.syncAuthState)
    }
  },
  watch: {
    '$route.fullPath': {
      immediate: true,
      handler() {
        this.syncAuthState()
        this.ensureCurrentUser()
        this.addVisitedTag()
      }
    }
  },
  methods: {
    syncAuthState() {
      this.hasToken = Boolean(getAdminToken())
      this.currentUser = getAdminUser()
      if (isStatisticsOnlyUser(this.currentUser)) {
        const allowedPaths = ['/analytics', '/function-usage', '/user-activity']
        this.visitedTags = this.visitedTags.filter(tag => allowedPaths.includes(tag.path))
      }
    },
    addVisitedTag() {
      if (this.isLoginPage) {
        return
      }
      const path = this.$route.path === '/' ? this.landingPath : (this.$route.path || this.landingPath)
      const title = this.$route.meta && this.$route.meta.title ? this.$route.meta.title : (path === this.landingPath ? (this.statisticsOnly ? '统计分析' : '首页概览') : '当前页面')
      if (!this.visitedTags.some(tag => tag.path === this.landingPath)) {
        this.visitedTags.push({ path: this.landingPath, title: this.statisticsOnly ? '统计分析' : '首页概览' })
      }
      if (!this.visitedTags.some(tag => tag.path === path)) {
        this.visitedTags.push({ path, title })
      }
    },
    closeTag(path) {
      const index = this.visitedTags.findIndex(tag => tag.path === path)
      if (index === -1 || path === this.landingPath) {
        return
      }
      this.visitedTags.splice(index, 1)
      if (this.$route.path === path) {
        const fallback = this.visitedTags[index - 1] || this.visitedTags[index] || this.visitedTags[0]
        this.$router.push(fallback ? fallback.path : this.landingPath)
      }
    },
    async ensureCurrentUser() {
      if (this.isLoginPage || !this.hasToken || this.currentUser || this.syncingCurrentUser) {
        return
      }
      this.syncingCurrentUser = true
      try {
        const data = await http.get('/admin/api/auth/me')
        const user = data && data.user ? data.user : data
        if (user) {
          setAdminUser(user)
        }
      } catch (error) {
        if (error && error.message && error.message.indexOf('登录已失效') > -1) {
          return
        }
        this.$message.error((error && error.message) || '获取当前管理员信息失败')
      } finally {
        this.syncingCurrentUser = false
        this.syncAuthState()
      }
    },
    openPasswordDialog() {
      if (!this.canChangePassword) {
        this.$message.info('BBP统一账号的密码请在 PHIS 门户中修改')
        return
      }
      this.passwordDialogVisible = true
    },
    resetPasswordForm() {
      this.passwordSubmitting = false
      if (this.$refs.passwordFormRef) {
        this.$refs.passwordFormRef.resetFields()
      } else {
        this.passwordForm = {
          oldPassword: '',
          newPassword: '',
          confirmPassword: ''
        }
      }
    },
    submitPasswordForm() {
      this.$refs.passwordFormRef.validate(async valid => {
        if (!valid) {
          return
        }
        if (this.passwordForm.newPassword.length < 6) {
          this.$message.error('新密码至少 6 位')
          return
        }
        if (this.passwordForm.newPassword !== this.passwordForm.confirmPassword) {
          this.$message.error('两次输入的新密码不一致')
          return
        }
        if (this.passwordForm.oldPassword === this.passwordForm.newPassword) {
          this.$message.error('新密码不能与当前密码相同')
          return
        }

        this.passwordSubmitting = true
        try {
          await http.put('/admin/api/auth/password', {
            oldPassword: this.passwordForm.oldPassword,
            newPassword: this.passwordForm.newPassword,
            confirmPassword: this.passwordForm.confirmPassword
          })
          this.$message.success('密码修改成功')
          this.passwordDialogVisible = false
        } catch (error) {
          this.$message.error((error && error.message) || '密码修改失败')
        } finally {
          this.passwordSubmitting = false
        }
      })
    },
    async handleLogout() {
      this.passwordDialogVisible = false
      try {
        await http.post('/admin/api/auth/logout')
      } catch (error) {
        // 无状态退出时允许忽略接口失败。
      }
      clearAdminAuth()
      this.visitedTags = []
      this.$router.replace('/login')
    }
  }
}
</script>

<style scoped>
.app-root {
  min-height: 100vh;
}
</style>
