import Vue from 'vue'
import Router from 'vue-router'
import { getAdminUser, isAuthenticated } from '../utils/auth'
import { resolveAdminRouteRedirect } from '../utils/access'
import { adminPageMeta } from './navigation'
import { registerRouteErrorHandler } from './errors'

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
const InpatientEmrLogView = () => import('../views/InpatientEmrLogView.vue')
const EmrQcView = () => import('../views/EmrQcView.vue')
const MedicalAssistantLogView = () => import('../views/MedicalAssistantLogView.vue')
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
    { path: '/overview', component: OverviewView, meta: adminPageMeta('/overview') },
    { path: '/users', component: UserView, meta: adminPageMeta('/users') },
    { path: '/ai-user-permissions', component: AiUserPermissionView, meta: adminPageMeta('/ai-user-permissions') },
    { path: '/roles', component: RoleView, meta: adminPageMeta('/roles') },
    { path: '/regions', component: RegionView, meta: adminPageMeta('/regions') },
    { path: '/orgs', component: OrgView, meta: adminPageMeta('/orgs') },
    { path: '/devices', component: DeviceView, meta: adminPageMeta('/devices') },
    { path: '/client-usage', component: ClientUsageView, meta: adminPageMeta('/client-usage') },
    { path: '/configs', component: ConfigView, meta: adminPageMeta('/configs') },
    { path: '/prompts', component: PromptView, meta: adminPageMeta('/prompts') },
    { path: '/symptom-templates', component: SymptomTemplateView, meta: adminPageMeta('/symptom-templates') },
    { path: '/emr-templates', component: EmrTemplateView, meta: adminPageMeta('/emr-templates') },
    { path: '/lis-result-entry', redirect: '/exam-result-entry' },
    { path: '/exam-result-entry', component: LisResultEntryView, meta: adminPageMeta('/exam-result-entry') },
    { path: '/releases', component: ReleaseView, meta: adminPageMeta('/releases') },
    { path: '/logs', component: LogView, meta: adminPageMeta('/logs') },
    { path: '/inpatient-emr-logs', component: InpatientEmrLogView, meta: adminPageMeta('/inpatient-emr-logs') },
    { path: '/emr-qc', component: EmrQcView, meta: adminPageMeta('/emr-qc') },
    { path: '/medical-assistant-logs', component: MedicalAssistantLogView, meta: adminPageMeta('/medical-assistant-logs') },
    { path: '/user-logs', component: UserLogView, meta: adminPageMeta('/user-logs') },
    { path: '/business-workflow-debug', component: BusinessWorkflowDebugView, meta: adminPageMeta('/business-workflow-debug') },
    { path: '/feedbacks', component: FeedbackView, meta: adminPageMeta('/feedbacks') },
    { path: '/recommendation-preferences', component: RecommendationPreferenceView, meta: adminPageMeta('/recommendation-preferences') },
    { path: '/patient-memories', component: PatientMemoryView, meta: adminPageMeta('/patient-memories') },
    { path: '/analytics', component: AnalyticsView, meta: adminPageMeta('/analytics') },
    { path: '/function-usage', component: XiaoshanFunctionUsageView, meta: adminPageMeta('/function-usage') },
    { path: '/standard-function-usage', component: FunctionUsageView, meta: { title: '辅诊功能统计（通用版）', organizationStatistics: true } },
    { path: '/user-activity', component: UserActivityView, meta: adminPageMeta('/user-activity') },
    { path: '/security-rejections', component: SecurityRejectionView, meta: adminPageMeta('/security-rejections') },
    { path: '/security-analytics', component: SecurityAnalyticsView, meta: adminPageMeta('/security-analytics') },
    { path: '*', redirect: '/overview' }
  ]
})

registerRouteErrorHandler(router, options => Vue.prototype.$message(options))

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
