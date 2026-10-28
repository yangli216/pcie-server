import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'
import Vue from 'vue'
import * as compiler from 'vue/compiler-sfc'
import { adminMenu, adminPageMeta, visibleAdminMenu } from '../src/router/navigation.js'
import { resolveAdminRouteRedirect } from '../src/utils/access.js'
import { registerRouteErrorHandler } from '../src/router/errors.js'
import viteConfig from '../vite.config.js'

const pagesOf = menu => menu.flatMap(item => item.children || [item])
const pathsOf = menu => pagesOf(menu).map(page => page.path)
const bbpAdmin = { authProvider: 'BBP', roles: ['SYSTEM_ADMIN'] }
const analyst = { authProvider: 'BBP', roles: ['ORG_ANALYST'] }
const analystPaths = ['/analytics', '/function-usage', '/user-activity']

test('all existing menu pages appear exactly once in six distinct directories and the home entry', () => {
  const expected = [
    '/overview', '/users', '/ai-user-permissions', '/roles', '/regions', '/orgs', '/devices',
    '/client-usage', '/configs', '/prompts', '/symptom-templates', '/emr-templates',
    '/exam-result-entry', '/releases', '/logs', '/inpatient-emr-logs', '/emr-qc',
    '/medical-assistant-logs', '/user-logs', '/business-workflow-debug', '/feedbacks',
    '/recommendation-preferences', '/patient-memories', '/analytics', '/function-usage',
    '/user-activity', '/security-rejections', '/security-analytics'
  ]
  assert.deepEqual(pathsOf(adminMenu).sort(), expected.sort())
  assert.equal(new Set(pathsOf(adminMenu)).size, expected.length)
  const groups = adminMenu.filter(item => item.children)
  assert.equal(groups.length, 6)
  assert.equal(new Set(groups.map(group => group.id)).size, 6)
  assert.equal(new Set(pagesOf(adminMenu).map(page => page.label)).size, expected.length)
  assert.equal(adminMenu[0].path, '/overview')
})

test('local and unknown sessions hide BBP pages without changing the shared menu', () => {
  const original = JSON.stringify(adminMenu)
  for (const user of [null, { authProvider: 'LOCAL', roles: ['admin'] }]) {
    const menu = visibleAdminMenu(user)
    assert.equal(pathsOf(menu).length, 26)
    assert.ok(!pathsOf(menu).includes('/ai-user-permissions'))
    assert.ok(!pathsOf(menu).includes('/emr-qc'))
    assert.ok(menu.every(item => !item.children || item.children.length))
  }
  assert.equal(JSON.stringify(adminMenu), original)
})

test('BBP administrators retain all existing pages, including mixed analyst roles', () => {
  for (const roles of [['SYSTEM_ADMIN'], ['ORG_ADMIN'], ['ORG_ANALYST', 'SYSTEM_ADMIN'], ['ORG_ANALYST', 'ORG_ADMIN']]) {
    assert.deepEqual(pathsOf(visibleAdminMenu({ authProvider: 'BBP', roles })), pathsOf(adminMenu))
  }
})

test('organization analysts only see their three statistics pages and no empty directories', () => {
  const menu = visibleAdminMenu(analyst)
  assert.equal(menu.length, 1)
  assert.equal(menu[0].id, 'analytics')
  assert.deepEqual(pathsOf(menu), analystPaths)
  assert.ok(!pathsOf(menu).includes('/emr-qc'))
})

test('shared titles and access metadata preserve direct route restrictions', () => {
  for (const page of pagesOf(adminMenu)) {
    const meta = adminPageMeta(page.path)
    const route = { matched: [{ meta }] }
    assert.equal(meta.title, page.label)
    assert.equal(resolveAdminRouteRedirect(route, analyst), analystPaths.includes(page.path) ? '' : '/analytics')
    assert.equal(resolveAdminRouteRedirect(route, { authProvider: 'LOCAL' }), page.bbpOnly ? '/overview' : '')
    assert.equal(resolveAdminRouteRedirect(route, bbpAdmin), '')
  }
  assert.throws(() => adminPageMeta('/unknown-page'))
})

test('each menu URL uses shared metadata in the actual router and compatibility routes remain', async () => {
  const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
  const sharedRoutes = [...router.matchAll(/path: '([^']+)', component: \w+, meta: adminPageMeta\('([^']+)'\)/g)]
  assert.deepEqual(sharedRoutes.map(match => match[1]).sort(), pathsOf(adminMenu).sort())
  assert.ok(sharedRoutes.every(match => match[1] === match[2]))
  assert.ok(router.includes("{ path: '/lis-result-entry', redirect: '/exam-result-entry' }"))
  assert.ok(router.includes("path: '/standard-function-usage', component: FunctionUsageView"))
})

const sidebarSource = await readFile(new URL('../src/components/layout/AdminSidebar.vue', import.meta.url), 'utf8')
const sidebarDescriptor = compiler.parse({ source: sidebarSource, filename: 'AdminSidebar.vue' })
const sidebarOptions = (await import(`data:text/javascript;base64,${Buffer.from(sidebarDescriptor.script.content).toString('base64')}`)).default

function createSidebar(path, menu = visibleAdminMenu(bbpAdmin)) {
  const route = Vue.observable({ path })
  const component = new Vue({
    ...sidebarOptions,
    propsData: { menuItems: menu },
    computed: { ...sidebarOptions.computed, $route: () => route }
  })
  return { component, route }
}

test('sidebar opens the active directory on direct entry and navigation, and supports manual toggles', async () => {
  const { component, route } = createSidebar('/emr-qc')
  assert.deepEqual([...component.expandedGroupIds], ['clinical'])
  component.toggleGroup('clinical')
  assert.deepEqual([...component.expandedGroupIds], [])
  component.toggleGroup('audit')
  route.path = '/configs'
  await Vue.nextTick()
  assert.equal(component.currentGroupId, 'ai')
  assert.deepEqual([...component.expandedGroupIds], ['audit', 'ai'])
  route.path = '/emr-qc'
  await Vue.nextTick()
  assert.ok(component.expandedGroupIds.includes('clinical'))
  component.$destroy()
})

test('sidebar handles home, delayed menu loading, and permission-filtered menus', async () => {
  const { component, route } = createSidebar('/overview', [])
  assert.deepEqual([...component.expandedGroupIds], [])
  route.path = '/analytics'
  await Vue.nextTick()
  component.menuItems = visibleAdminMenu(analyst)
  await Vue.nextTick()
  assert.equal(component.currentGroupId, 'analytics')
  assert.deepEqual([...component.expandedGroupIds], ['analytics'])
  component.$destroy()
})

test('lazy route failures show an actionable message without exposing technical details', () => {
  let onError
  const notifications = []
  registerRouteErrorHandler({ onError: callback => { onError = callback } }, options => notifications.push(options))
  onError(new Error('Failed to fetch dynamically imported module: http://internal-host/assets/private-path.js'))
  assert.equal(notifications.length, 1)
  assert.equal(notifications[0].type, 'error')
  assert.equal(notifications[0].showClose, true)
  assert.match(notifications[0].message, /刷新页面后重试/)
  assert.ok(!notifications[0].message.includes('internal-host'))
})

test('development dependency cache survives npm ci and lazy-only dependencies are prebuilt', () => {
  assert.equal(viteConfig.cacheDir, '.vite-cache')
  for (const dependency of ['echarts', 'element-ui/lib/timeline', 'element-ui/lib/timeline-item']) {
    assert.ok(viteConfig.optimizeDeps.include.includes(dependency))
  }
})
