import Vue from 'vue'
import Router from 'vue-router'
import { getAdminUser, isAuthenticated } from '../utils/auth'
import { resolveAdminRouteRedirect } from '../utils/access'

Vue.use(Router)

const LoginView = () => import('../views/LoginView.vue')
const OverviewView = () => import('../views/OverviewView.vue')
const UserView = () => import('../views/UserView.vue')
const AiUserPermissionView = () => import('../views/AiUserPermissionView.vue')
const RoleView = () => import('../views/RoleView.vue')
const RegionView = () => import('../views/RegionView.vue')
const OrgView = () => import('../views/OrgView.vue')
const DeviceView = () => import('../views/DeviceView.vue')
const ClientUsageView = () => import('../views/ClientUsageView.vue')
const ConfigView = () => import('../views/ConfigView.vue')
const PromptView = () => import('../views/PromptView.vue')
const SymptomTemplateView = () => import('../views/SymptomTemplateView.vue')
const EmrTemplateView = () => import('../views/EmrTemplateView.vue')
const LisResultEntryView = () => import('../views/LisResultEntryView.vue')
const ReleaseView = () => import('../views/ReleaseView.vue')
const LogView = () => import('../views/LogView.vue')
const UserLogView = () => import('../views/UserLogView.vue')
const BusinessWorkflowDebugView = () => import('../views/BusinessWorkflowDebugView.vue')
const FeedbackView = () => import('../views/FeedbackView.vue')
const RecommendationPreferenceView = () => import('../views/RecommendationPreferenceView.vue')
const PatientMemoryView = () => import('../views/PatientMemoryView.vue')
const AnalyticsView = () => import('../views/AnalyticsView.vue')
const FunctionUsageView = () => import('../views/FunctionUsageView.vue')
const XiaoshanFunctionUsageView = () => import('../views/XiaoshanFunctionUsageView.vue')
const UserActivityView = () => import('../views/UserActivityView.vue')
const SecurityRejectionView = () => import('../views/SecurityRejectionView.vue')
const SecurityAnalyticsView = () => import('../views/SecurityAnalyticsView.vue')

function resolveRedirectPath(value) {
  return typeof value === 'string' && value.indexOf('/') === 0 ? value : '/overview'
}

const router = new Router({
  mode: 'hash',
  routes: [
    { path: '/login', component: LoginView, meta: { title: '管理员登录', public: true } },
    { path: '/', redirect: '/overview' },
    { path: '/overview', component: OverviewView, meta: { title: '首页概览' } },
    { path: '/users', component: UserView, meta: { title: '用户管理' } },
    { path: '/ai-user-permissions', component: AiUserPermissionView, meta: { title: 'AI 使用权限', bbpOnly: true } },
    { path: '/roles', component: RoleView, meta: { title: '角色管理' } },
    { path: '/regions', component: RegionView, meta: { title: '区域管理' } },
    { path: '/orgs', component: OrgView, meta: { title: '机构管理' } },
    { path: '/devices', component: DeviceView, meta: { title: '令牌管理' } },
    { path: '/client-usage', component: ClientUsageView, meta: { title: '客户端使用情况' } },
    { path: '/configs', component: ConfigView, meta: { title: '模型配置' } },
    { path: '/prompts', component: PromptView, meta: { title: '提示词配置' } },
    { path: '/symptom-templates', component: SymptomTemplateView, meta: { title: '症状模板' } },
    { path: '/emr-templates', component: EmrTemplateView, meta: { title: '病历模板' } },
    { path: '/lis-result-entry', redirect: '/exam-result-entry' },
    { path: '/exam-result-entry', component: LisResultEntryView, meta: { title: '检验检查回写' } },
    { path: '/releases', component: ReleaseView, meta: { title: '版本发布' } },
    { path: '/logs', component: LogView, meta: { title: '操作日志' } },
    { path: '/user-logs', component: UserLogView, meta: { title: '用户日志' } },
    { path: '/business-workflow-debug', component: BusinessWorkflowDebugView, meta: { title: '业务调试' } },
    { path: '/feedbacks', component: FeedbackView, meta: { title: '反馈管理' } },
    { path: '/recommendation-preferences', component: RecommendationPreferenceView, meta: { title: '推荐偏好' } },
    { path: '/patient-memories', component: PatientMemoryView, meta: { title: '患者记忆' } },
    { path: '/analytics', component: AnalyticsView, meta: { title: '统计分析', organizationStatistics: true } },
    { path: '/function-usage', component: XiaoshanFunctionUsageView, meta: { title: '辅诊功能', organizationStatistics: true } },
    { path: '/standard-function-usage', component: FunctionUsageView, meta: { title: '辅诊功能（通用版）', organizationStatistics: true } },
    { path: '/user-activity', component: UserActivityView, meta: { title: '用户活跃度', organizationStatistics: true } },
    { path: '/security-rejections', component: SecurityRejectionView, meta: { title: '安全拦截' } },
    { path: '/security-analytics', component: SecurityAnalyticsView, meta: { title: '安全分析' } },
    { path: '*', redirect: '/overview' }
  ]
})

router.beforeEach((to, from, next) => {
  const loggedIn = isAuthenticated()
  const isPublicRoute = to.matched.some(record => record.meta && record.meta.public)

  if (isPublicRoute) {
    if (loggedIn && to.path === '/login') {
      next(resolveRedirectPath(to.query.redirect))
      return
    }
    next()
    return
  }

  if (!loggedIn) {
    next({
      path: '/login',
      query: {
        redirect: to.fullPath || '/overview'
      }
    })
    return
  }

  const currentUser = getAdminUser()
  const accessRedirect = resolveAdminRouteRedirect(to, currentUser)
  if (accessRedirect) {
    next(accessRedirect)
    return
  }

  next()
})

router.afterEach(to => {
  if (typeof document !== 'undefined') {
    document.title = `${to.meta.title || '管理端'} - 全医慧助（PCIE）`
  }
})

export default router
