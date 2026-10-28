import { canAccessRestrictedPage } from '../utils/access.js'

// 页面名称与可见性在这里维护，路由和导航共同使用。
export const adminMenu = [
  { path: '/overview', label: '首页概览', icon: 'el-icon-s-home' },
  {
    id: 'analytics', label: '统计分析', icon: 'el-icon-data-analysis',
    children: [
      { path: '/analytics', label: '统计概览', icon: 'el-icon-pie-chart', organizationStatistics: true },
      { path: '/function-usage', label: '辅诊功能统计', icon: 'el-icon-s-data', organizationStatistics: true },
      { path: '/user-activity', label: '用户活跃统计', icon: 'el-icon-data-line', organizationStatistics: true },
      { path: '/client-usage', label: '客户端使用统计', icon: 'el-icon-monitor' },
      { path: '/recommendation-preferences', label: '推荐偏好分析', icon: 'el-icon-sort' }
    ]
  },
  {
    id: 'clinical', label: '临床业务', icon: 'el-icon-first-aid-kit',
    children: [
      { path: '/emr-qc', label: '门诊病历质控', icon: 'el-icon-document-checked', bbpOnly: true, containedLayout: true, emrQcPermission: true },
      { path: '/exam-result-entry', label: '检验检查回写', icon: 'el-icon-edit-outline' },
      { path: '/patient-memories', label: '患者记忆', icon: 'el-icon-collection' }
    ]
  },
  {
    id: 'ai', label: 'AI 配置', icon: 'el-icon-cpu',
    children: [
      { path: '/configs', label: '模型配置', icon: 'el-icon-setting' },
      { path: '/prompts', label: '提示词配置', icon: 'el-icon-edit-outline' },
      { path: '/symptom-templates', label: '症状模板', icon: 'el-icon-document-checked' },
      { path: '/emr-templates', label: '病历模板', icon: 'el-icon-document-copy' }
    ]
  },
  {
    id: 'audit', label: '日志审计', icon: 'el-icon-notebook-2',
    children: [
      { path: '/user-logs', label: '问诊日志', icon: 'el-icon-notebook-2' },
      { path: '/inpatient-emr-logs', label: '住院病历日志', icon: 'el-icon-document-copy' },
      { path: '/medical-assistant-logs', label: '医学助手日志', icon: 'el-icon-chat-dot-round' },
      { path: '/logs', label: '操作日志', icon: 'el-icon-document' }
    ]
  },
  {
    id: 'operations', label: '系统运维', icon: 'el-icon-s-tools',
    children: [
      { path: '/releases', label: '客户端发布', icon: 'el-icon-upload' },
      { path: '/feedbacks', label: '用户反馈', icon: 'el-icon-chat-dot-square' },
      { path: '/business-workflow-debug', label: '业务流程调试', icon: 'el-icon-connection' },
      { path: '/security-rejections', label: '安全拦截记录', icon: 'el-icon-lock' },
      { path: '/security-analytics', label: '安全风险分析', icon: 'el-icon-warning-outline' }
    ]
  },
  {
    id: 'administration', label: '基础管理', icon: 'el-icon-office-building',
    children: [
      { path: '/users', label: '用户管理', icon: 'el-icon-user' },
      { path: '/roles', label: '角色管理', icon: 'el-icon-s-custom' },
      { path: '/ai-user-permissions', label: 'AI 使用权限', icon: 'el-icon-circle-check', bbpOnly: true },
      { path: '/regions', label: '区域管理', icon: 'el-icon-location' },
      { path: '/orgs', label: '机构管理', icon: 'el-icon-office-building' },
      { path: '/devices', label: '设备令牌', icon: 'el-icon-key' }
    ]
  }
]

const pages = adminMenu.flatMap(item => item.children || [item])

export function adminPageMeta(path) {
  const page = pages.find(item => item.path === path)
  if (!page) throw new Error(`未配置管理端页面：${path}`)
  return {
    title: page.label,
    ...(page.containedLayout ? { containedLayout: true } : {}),
    ...(page.bbpOnly ? { bbpOnly: true } : {}),
    ...(page.emrQcPermission ? { emrQcPermission: true } : {}),
    ...(page.organizationStatistics ? { organizationStatistics: true } : {})
  }
}

export function visibleAdminMenu(user) {
  const canSee = page => canAccessRestrictedPage(page, user) &&
    (!page.bbpOnly || Boolean(user && user.authProvider === 'BBP'))

  return adminMenu.reduce((result, item) => {
    if (item.children) {
      const children = item.children.filter(canSee)
      if (children.length) result.push({ ...item, children })
    } else if (canSee(item)) {
      result.push(item)
    }
    return result
  }, [])
}
