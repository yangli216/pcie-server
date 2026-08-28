export const ORG_ANALYST = 'ORG_ANALYST'
export const ORG_ADMIN = 'ORG_ADMIN'
export const SYSTEM_ADMIN = 'SYSTEM_ADMIN'

export function userRoles(user) {
  return user && Array.isArray(user.roles) ? user.roles : []
}

export function isStatisticsOnlyUser(user) {
  const roles = userRoles(user)
  return Boolean(user && user.authProvider === 'BBP' && roles.includes(ORG_ANALYST) &&
    !roles.includes(SYSTEM_ADMIN) && !roles.includes(ORG_ADMIN))
}

function routeHasMeta(route, key) {
  return Boolean(route && Array.isArray(route.matched) &&
    route.matched.some(record => record.meta && record.meta[key]))
}

export function resolveAdminRouteRedirect(route, user) {
  if (isStatisticsOnlyUser(user) && !routeHasMeta(route, 'organizationStatistics')) {
    return '/analytics'
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
