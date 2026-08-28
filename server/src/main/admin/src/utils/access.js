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
