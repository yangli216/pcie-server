export const ORG_ANALYST = 'ORG_ANALYST'
export const ORG_ADMIN = 'ORG_ADMIN'
export const SYSTEM_ADMIN = 'SYSTEM_ADMIN'
export const EMR_QC_REVIEWER = 'EMR_QC_REVIEWER'

export function userRoles(user) {
  return user && Array.isArray(user.roles) ? user.roles : []
}

export function isStatisticsOnlyUser(user) {
  const roles = userRoles(user)
  return Boolean(user && user.authProvider === 'BBP' && roles.includes(ORG_ANALYST) &&
    !roles.includes(SYSTEM_ADMIN) && !roles.includes(ORG_ADMIN) && !roles.includes(EMR_QC_REVIEWER))
}

export function isRestrictedBbpUser(user) {
  const roles = userRoles(user)
  return Boolean(user && user.authProvider === 'BBP' &&
    !roles.includes(SYSTEM_ADMIN) && !roles.includes(ORG_ADMIN) &&
    (roles.includes(ORG_ANALYST) || roles.includes(EMR_QC_REVIEWER)))
}

export function canAccessRestrictedPage(page, user) {
  if (!isRestrictedBbpUser(user)) return true
  const roles = userRoles(user)
  return Boolean((page.organizationStatistics && roles.includes(ORG_ANALYST)) ||
    (page.emrQcPermission && roles.includes(EMR_QC_REVIEWER)))
}

export function defaultAdminLandingPath(user) {
  const roles = userRoles(user)
  if (isRestrictedBbpUser(user)) {
    return roles.includes(ORG_ANALYST) ? '/analytics' : '/emr-qc'
  }
  return '/overview'
}

function routeHasMeta(route, key) {
  return Boolean(route && Array.isArray(route.matched) &&
    route.matched.some(record => record.meta && record.meta[key]))
}

export function resolveAdminRouteRedirect(route, user) {
  if (isRestrictedBbpUser(user)) {
    const roles = userRoles(user)
    const allowed = (routeHasMeta(route, 'organizationStatistics') && roles.includes(ORG_ANALYST)) ||
      (routeHasMeta(route, 'emrQcPermission') && roles.includes(EMR_QC_REVIEWER))
    if (!allowed) return roles.includes(ORG_ANALYST) ? '/analytics' : '/emr-qc'
  }
  if (routeHasMeta(route, 'bbpOnly') && (!user || user.authProvider !== 'BBP')) {
    return '/overview'
  }
  return ''
}

export function isBbpOrganizationScopedUser(user) {
  return Boolean(user && user.authProvider === 'BBP' && !userRoles(user).includes(SYSTEM_ADMIN))
}

export function bbpOrganizationOption(user) {
  if (!user || !user.bbpOrgId) return null
  const id = String(user.bbpOrgId)
  const name = user.bbpOrgName || user.bbpOrgCode || id
  return {
    id,
    label: name === id ? id : `${name}（${id}）`
  }
}
