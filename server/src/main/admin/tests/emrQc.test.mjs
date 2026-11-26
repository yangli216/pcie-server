import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import * as compiler from 'vue/compiler-sfc'
import { canReviewQc, proxyRefillLabel } from '../src/utils/emrQcQuery.js'
import { isWithinOneCalendarYear, oneYearAfter } from '../src/utils/emrQcDateRange.js'

test('proxy identity remains explicit and historical results require verification', () => {
  const proxy = { proxyRefill: true, resultSource: 'proxy_refill', score: 100 }
  assert.equal(proxyRefillLabel(proxy), '代配药 · 免质控')
  assert.equal(proxyRefillLabel({ ...proxy, stale: true }), '上次代配药（待重新核验）')
  assert.equal(proxyRefillLabel({ ...proxy, snapshot: true }), '上次代配药（待重新核验）')
  assert.equal(proxyRefillLabel({ score: 100, resultSource: 'ai' }), '')
  assert.equal(proxyRefillLabel({ ...proxy, proxyRefill: false }), '')
  assert.equal(proxyRefillLabel(null), '')
})

test('ordinary current results remain reviewable and exempt results cannot receive manual deductions', () => {
  const qc = { resultSource: 'ai', ruleVersion: 'emr-qc-rules-v11' }
  assert.equal(canReviewQc(qc, qc.ruleVersion), true)
  assert.equal(canReviewQc({ ...qc, resultSource: 'manual' }, qc.ruleVersion), true)
  assert.equal(canReviewQc({ ...qc, resultSource: 'proxy_refill' }, qc.ruleVersion), false)
  assert.equal(canReviewQc({ ...qc, stale: true }, qc.ruleVersion), false)
  assert.equal(canReviewQc(qc, 'emr-qc-rules-v10'), false)
  assert.equal(canReviewQc(qc, undefined), false)
})
import {
  departmentOptions,
  decorateEmrHtmlPreview,
  doctorsForDepartment,
  dimensionPercent,
  findingUsesStructuredPreview,
  findingLocation,
  findingCounts,
  normalizeEmrText,
  orderedFindings,
  resolveFindingTarget,
  setFindingCategory,
  setFindingIgnored,
  totalScore,
  totalWeight,
  updateFindingDeduction
} from '../src/utils/emrQcResults.js'

test('accepts at most one calendar year and clamps leap-day anniversaries', () => {
  assert.equal(oneYearAfter('2025-10-28'), '2026-10-28')
  assert.equal(isWithinOneCalendarYear('2025-10-28', '2026-10-28'), true)
  assert.equal(isWithinOneCalendarYear('2025-10-28', '2026-10-29'), false)
  assert.equal(oneYearAfter('2024-02-29'), '2025-02-28')
  assert.equal(isWithinOneCalendarYear('2024-02-29', '2025-02-28'), true)
  assert.equal(isWithinOneCalendarYear('2024-02-29', '2025-03-01'), false)
})

test('prioritizes severe findings, preserves order within severity, and never mutates the result', () => {
  const qc = { findings: [
    { severity: 'low', issue: 'low' }, { severity: 'high', issue: 'high-first' },
    { severity: 'info', issue: 'info' }, { severity: 'high', issue: 'high-second' },
    { severity: 'medium', issue: 'medium' }, { severity: 'future', issue: 'unknown' }
  ] }
  const before = JSON.stringify(qc)
  assert.deepEqual(orderedFindings(qc).map(x => x.issue), ['high-first', 'high-second', 'medium', 'low', 'info', 'unknown'])
  assert.equal(JSON.stringify(qc), before)
  assert.deepEqual(orderedFindings(qc, 'high').map(x => x.originalIndex), [1, 3])
  assert.deepEqual(orderedFindings(qc, 'info').map(x => x.issue), ['info'])
})

test('supports missing results, empty filtered lists, and safe score widths', () => {
  assert.deepEqual(orderedFindings(null), [])
  assert.deepEqual(orderedFindings({}), [])
  assert.deepEqual(orderedFindings({ findings: [{ severity: 'low' }] }, 'high'), [])
  assert.equal(dimensionPercent(125), 100)
  assert.equal(dimensionPercent(-20), 0)
  assert.equal(dimensionPercent('not-a-score'), 0)
  assert.equal(dimensionPercent(Infinity), 0)
  assert.equal(dimensionPercent(83.5), 83.5)
})

test('calculates dimension progress and sums the nine-dimension 100-point rubric', () => {
  assert.equal(dimensionPercent(5, 10), 50)
  assert.equal(dimensionPercent(15, 20), 75)
  assert.equal(dimensionPercent(1, 0), 0)
  const qc = { dimensions: [
    { weight: 10, score: 9 }, { weight: 5, score: 5 }, { weight: 20, score: 18 },
    { weight: 10, score: 10 }, { weight: 15, score: 14 }, { weight: 10, score: 10 },
    { weight: 20, score: 20 }, { weight: 5, score: 4 }, { weight: 5, score: 5 }
  ] }
  assert.equal(totalWeight(qc), 100)
  assert.equal(totalScore(qc), 95)
  assert.equal(totalWeight(null), 100)
  assert.equal(totalWeight({ dimensions: [] }), 100)
  assert.equal(totalScore(null), 0)
})

test('adjusts a dimension by the deduction difference and enforces score bounds', () => {
  const dimensions = [{ code: 'present_illness', name: '现病史', score: 15, weight: 20 }]
  const finding = { category: '现病史', deduction: 5, ignored: false }

  updateFindingDeduction(dimensions, finding, 8)
  assert.equal(finding.deduction, 8)
  assert.equal(dimensions[0].score, 12)
  updateFindingDeduction(dimensions, finding, 99)
  assert.equal(finding.deduction, 20)
  assert.equal(dimensions[0].score, 0)
  updateFindingDeduction(dimensions, finding, -1)
  assert.equal(finding.deduction, 0)
  assert.equal(dimensions[0].score, 20)
})

test('ignoring and restoring findings updates scores and active finding counts once', () => {
  const dimensions = [{ code: 'patient_info', name: '患者基础信息', score: 5, weight: 10 }]
  const finding = { category: '患者基础信息', deduction: 3, ignored: false, severity: 'high' }
  assert.deepEqual(findingCounts([finding]), { total: 1, high: 1 })

  setFindingIgnored(dimensions, finding, true)
  setFindingIgnored(dimensions, finding, true)
  assert.equal(dimensions[0].score, 10)
  assert.deepEqual(findingCounts([finding]), { total: 0, high: 0 })
  updateFindingDeduction(dimensions, finding, 2)
  assert.equal(dimensions[0].score, 10)

  setFindingIgnored(dimensions, finding, false)
  assert.equal(dimensions[0].score, 8)
  assert.deepEqual(findingCounts([finding]), { total: 1, high: 1 })
})

test('moving a finding restores its old dimension and bounds the new deduction', () => {
  const dimensions = [
    { code: 'present_illness', name: '现病史', score: 10, weight: 20 },
    { code: 'chief_complaint', name: '主诉', score: 3, weight: 5 }
  ]
  const finding = { category: '现病史', deduction: 8, ignored: false }

  setFindingCategory(dimensions, finding, 'chief_complaint')
  assert.equal(finding.category, '主诉')
  assert.equal(finding.deduction, 5)
  assert.equal(dimensions[0].score, 20)
  assert.equal(dimensions[1].score, 0)
})

test('recalculates capped scores after ignoring and restoring overlapping deductions', () => {
  const dimensions = [{ code: 'chief_complaint', name: '主诉', score: 0, weight: 5 }]
  const findings = [{ category: '主诉', deduction: 5 }, { category: '主诉', deduction: 4 }]
  setFindingIgnored(dimensions, findings[0], true, findings)
  assert.equal(dimensions[0].score, 1)
  setFindingIgnored(dimensions, findings[0], false, findings)
  assert.equal(dimensions[0].score, 0)
})

test('department choices are unique and narrow doctors without excluding unassigned doctors when cleared', () => {
  const doctors = [{ userId: '1', deptName: '内科' }, { userId: '2', deptName: '外科' }, { userId: '3', deptName: '内科' }, { userId: '4' }]
  assert.deepEqual(new Set(departmentOptions(doctors)), new Set(['内科', '外科']))
  assert.deepEqual(doctorsForDepartment(doctors, '内科').map(x => x.userId), ['1', '3'])
  assert.equal(doctorsForDepartment(doctors, '').length, 4)
})

test('resolves a finding to an exact section and excerpt field', () => {
  const sections = [
    { name: '既往史', fields: [{ name: '过敏史', value: '否认药物过敏' }] },
    { name: '体格检查', fields: [{ name: '血压', value: '130/90mmHg' }, { name: '专科检查', value: '咽部充血' }] }
  ]
  assert.deepEqual(resolveFindingTarget(sections, {
    sectionName: '查体与辅助检查',
    excerpt: '患者生命体征：血压 130/90mmHg。'
  }), { scope: 'section', sectionIndex: 1, fieldIndex: 0, label: '体格检查', matchedBy: 'excerpt' })
  assert.equal(normalizeEmrText('130 / 90 mmHg'), '13090mmhg')
})

test('uses aliases and exposes missing, metadata, and whole-record relationships', () => {
  const sections = [{ name: '处理', fields: [{ name: '处置', value: '口服药物' }] }]
  assert.equal(resolveFindingTarget(sections, { category: '诊疗措施' }).sectionIndex, 0)
  assert.deepEqual(resolveFindingTarget(sections, { sectionName: '主诉', issue: '缺少「主诉」章节内容。' }), {
    scope: 'missing', sectionIndex: -1, fieldIndex: -1, label: '主诉', matchedBy: 'missing'
  })
  assert.equal(resolveFindingTarget(sections, { category: '患者基础信息' }).scope, 'meta')
  assert.equal(resolveFindingTarget(sections, { sectionName: '全文' }).scope, 'record')
})

test('removes duplicate finding locations while preserving useful section detail', () => {
  assert.equal(findingLocation({ category: '主诉', sectionName: '主诉' }), '主诉')
  assert.equal(findingLocation({ category: '患者基础信息', sectionName: '全文' }), '患者基础信息')
  assert.equal(findingLocation({ category: '诊断', sectionName: '门急诊诊断' }), '诊断 · 门急诊诊断')
  assert.equal(findingLocation({ sectionName: '处理' }), '处理')
})

test('marks the matching sanitized PHIS section without changing unmatched previews', () => {
  const preview = '<header><h1>门诊病历</h1></header><p class="tag-article" data-emr-section="主诉"><span class="tag-article-title">主诉：</span></p><p class="tag-article" data-emr-section="个人史"><span class="tag-article-title">个人史：</span>否认吸烟史</p>'
  assert.equal(
    decorateEmrHtmlPreview(preview, '主诉：'),
    '<header><h1>门诊病历</h1></header><p class="tag-article" data-emr-section="主诉" data-emr-linked="true"><span class="tag-article-title">主诉：</span></p><p class="tag-article" data-emr-section="个人史"><span class="tag-article-title">个人史：</span>否认吸烟史</p>'
  )
  assert.equal(decorateEmrHtmlPreview(preview, '现病史'), preview)
  assert.equal(decorateEmrHtmlPreview('', '主诉'), '')
  const linkedPersonalHistory = decorateEmrHtmlPreview(preview, '个人史')
  assert.match(linkedPersonalHistory, /<header><h1>门诊病历<\/h1><\/header>/)
  assert.match(linkedPersonalHistory, /data-emr-section="主诉"/)
  assert.match(linkedPersonalHistory, /data-emr-section="个人史" data-emr-linked="true"/)
})

test('keeps PHIS original layout for finding linkage whenever a safe preview exists', () => {
  assert.equal(findingUsesStructuredPreview('<!doctype html><html></html>'), false)
  assert.equal(findingUsesStructuredPreview('  '), true)
  assert.equal(findingUsesStructuredPreview(null), true)
})

// Query orchestration uses deterministic fake services and never calls a real model.
const { applyQueryItems, isCurrentQcRuleVersion, pendingQueryRows, runPageQuery } = await import('../src/utils/emrQcQuery.js')

// Execute the real view method with fake API transport; no browser, PHIS or model is involved.
const viewSource = await readFile(new URL('../src/views/EmrQcView.vue', import.meta.url), 'utf8')
const viewScript = compiler.parse({ source: viewSource, filename: 'EmrQcView.vue' }).script.content
function detailView(analyzeRecord) {
  const imports = {}
  const script = viewScript.replace(/import\s+([\s\S]*?)\s+from\s+['"][^'"]+['"]\s*/g, (_, bindings) => {
    for (const name of bindings.replace(/[{}]/g, '').split(',').map(s => s.trim()).filter(Boolean)) imports[name] = () => {}
    return ''
  }).replace('export default', 'return')
  Object.assign(imports, { analyzeRecord, applyQueryItems, pendingQueryRows, canReviewQc, isCurrentQcRuleVersion, proxyRefillLabel })
  const options = new Function(...Object.keys(imports), script)(...Object.values(imports))
  const old = { proxyRefill: true, resultSource: 'proxy_refill', score: 100 }
  return {
    method: options.methods.analyzeDetail,
    state: { detail: { record: { idMedrecdoc: 'A' }, qc: old }, records: [{ idMedrecdoc: 'A', ...old }],
      $message: { success() {}, error() {} } }
  }
}

test('detail reanalysis hides old exempt results while pending and after a failure', async () => {
  let reject
  const { method, state } = detailView(() => new Promise((_, fail) => { reject = fail }))
  const run = method.call(state, true)
  assert.equal(state.analyzing, true)
  assert.equal(state.detail.qc, null)
  assert.equal(state.records[0].score, null)
  assert.equal(state.records[0].proxyRefill, false)
  reject(new Error('AI configuration missing'))
  await run
  assert.equal(state.detail.qc, null)
  assert.equal(state.records[0].qcStatus, 'failed')
  assert.equal(state.records[0].score, null)
  assert.match(state.detailQueryError, /AI configuration missing/)
  assert.equal(state.analyzing, false)
})

test('detail reanalysis restores only the newly returned result and its proxy identity', async () => {
  const analysis = { resultSource: 'ai', proxyRefill: false, score: 90, findings: [], findingCount: 1 }
  const { method, state } = detailView(async () => analysis)
  await method.call(state, true)
  assert.equal(state.detail.qc, analysis)
  assert.equal(state.records[0].score, 90)
  assert.equal(state.records[0].proxyRefill, false)
  assert.equal(state.detailQueryError, '')
})

test('new queries clear old exempt scores and batch success alone restores an explicit proxy marker', () => {
  const old = [{ idMedrecdoc: 'A', proxyRefill: true, resultSource: 'proxy_refill', score: 100 }]
  const pending = pendingQueryRows(old)
  assert.equal(pending[0].score, null)
  assert.equal(pending[0].proxyRefill, false)
  assert.equal(proxyRefillLabel(pending[0]), '')
  const success = applyQueryItems(pending, [{ idMedrecdoc: 'A', status: 'succeeded', score: 100,
    proxyRefill: true, resultSource: 'proxy_refill', findingCount: 0 }])
  assert.equal(proxyRefillLabel(success[0]), '代配药 · 免质控')
  assert.equal(success[0].findingCount, 0)
  const failed = applyQueryItems(success, [{ idMedrecdoc: 'A', status: 'failed', proxyRefill: true,
    resultSource: 'proxy_refill', score: 100 }])
  assert.equal(failed[0].proxyRefill, false)
  assert.equal(failed[0].score, null)
  const ordinary = applyQueryItems(success, [{ idMedrecdoc: 'A', status: 'succeeded', score: 90, resultSource: 'ai' }])
  assert.equal(ordinary[0].proxyRefill, false)
  assert.equal(ordinary[0].score, 90)
  const legacy = applyQueryItems(success, [{ idMedrecdoc: 'A', status: 'succeeded', score: 100 }])
  assert.equal(legacy[0].proxyRefill, false)
})

test('manual review compares the result to the server rule version without a hard-coded version', () => {
  for (const version of ['emr-qc-rules-v6', 'emr-qc-rules-v8', 'emr-qc-rules-v9', 'emr-qc-rules-v10', 'future-server-rule']) {
    assert.equal(isCurrentQcRuleVersion({ ruleVersion: version }, version), true)
  }
  assert.equal(isCurrentQcRuleVersion({ ruleVersion: 'emr-qc-rules-v9' }, 'emr-qc-rules-v10'), false)
  assert.equal(isCurrentQcRuleVersion({ ruleVersion: 'emr-qc-rules-v10' }, undefined), false)
  assert.equal(isCurrentQcRuleVersion({ ruleVersion: '' }, ''), false)
  assert.equal(isCurrentQcRuleVersion(null, 'emr-qc-rules-v10'), false)
})

test('new queries hide historical scores and failed items never restore them', () => {
  const rows = pendingQueryRows([{ idMedrecdoc: 'A', recordStatus: 'draft', score: 99, findingCount: 1, highSeverityCount: 1 }])
  assert.equal(rows[0].score, null)
  assert.equal(rows[0].recordStatus, 'draft')
  const failed = applyQueryItems(rows, [{ idMedrecdoc: 'A', status: 'failed', score: 99, errorMessage: 'session expired' }])
  assert.equal(failed[0].score, null)
  assert.equal(failed[0].queryError, 'session expired')
  assert.equal(failed[0].qcStatus, 'failed')
})

test('100-row query creates two sequential forced batches, continuing after a per-record failure', async () => {
  const records = Array.from({ length: 100 }, (_, i) => ({ idMedrecdoc: String(i) }))
  const batches = [], events = []
  await runPageQuery({ records, isCurrent: () => true, wait: async () => {},
    createTask: async (ids, force) => { assert.equal(force, true); batches.push(ids); events.push(`create${batches.length}`); return { taskId: String(batches.length) } },
    fetchTask: async id => { events.push(`fetch${id}`); return { status: 'partially_completed', items: batches[Number(id)-1].map((x,i) => ({ idMedrecdoc: x, status: i === 0 ? 'failed' : 'succeeded', score: 95 })) } },
    onTask: () => {}, onItems: () => {} })
  assert.deepEqual(batches.map(x => x.length), [50, 50])
  assert.deepEqual(events, ['create1', 'fetch1', 'create2', 'fetch2'])
})

test('polling active work reads progress without creating another analysis', async () => {
  let creates = 0, polls = 0, waits = 0
  await runPageQuery({ records: [{ idMedrecdoc: 'A' }], isCurrent: () => true,
    createTask: async () => { creates++; return { taskId: 'T' } },
    fetchTask: async () => ({ status: ++polls < 3 ? 'running' : 'completed', items: [{ idMedrecdoc: 'A', status: polls < 3 ? 'pending' : 'succeeded' }] }),
    wait: async () => { waits++ }, onTask: () => {}, onItems: () => {} })
  assert.equal(creates, 1)
  assert.equal(polls, 3)
  assert.equal(waits, 2)
})

test('newer queries suppress late progress and prevent the previous next batch', async () => {
  let current = true, creates = 0, displayed = 0
  await runPageQuery({ records: Array.from({length: 100},(_,i) => ({ idMedrecdoc: String(i) })), isCurrent: () => current,
    createTask: async () => { creates++; return { taskId: 'T' } },
    fetchTask: async () => { current = false; return { status: 'completed', items: [] } },
    onTask: () => { displayed++ }, onItems: () => { displayed++ } })
  assert.equal(creates, 1)
  assert.equal(displayed, 0)
})

test('cancelling while task creation is in flight does not claim that server task was cancelled', async () => {
  let current = true, creates = 0, reads = 0
  await runPageQuery({ records: [{ idMedrecdoc: 'A' }], isCurrent: () => current,
    createTask: async () => { creates++; current = false; return { taskId: 'ACCEPTED' } },
    fetchTask: async () => { reads++; }, onTask: () => {}, onItems: () => {} })
  assert.equal(creates, 1)
  assert.equal(reads, 0)
})

test('BUSY and polling errors stop the query without automatically retrying AI', async () => {
  let creates = 0
  await assert.rejects(runPageQuery({ records: [{ idMedrecdoc: 'A' }], isCurrent: () => true,
    createTask: async () => { creates++; throw new Error('BUSY') }, fetchTask: async () => {}, onTask: () => {}, onItems: () => {} }), /BUSY/)
  assert.equal(creates, 1)
  await assert.rejects(runPageQuery({ records: [{ idMedrecdoc: 'A' }], isCurrent: () => true,
    createTask: async () => ({ taskId: 'T' }), fetchTask: async () => { throw new Error('session expired') }, onTask: () => {}, onItems: () => {} }), /session expired/)
})

test('terminal interrupted tasks mark remaining items failed and preserve successful current scores', async () => {
  let rows = pendingQueryRows([{idMedrecdoc:'A'}, {idMedrecdoc:'B'}])
  await runPageQuery({ records: rows, isCurrent: () => true,
    createTask: async () => ({taskId:'T'}), fetchTask: async () => ({status:'failed',items:[{idMedrecdoc:'A',status:'succeeded',score:88,findingCount:2,highSeverityCount:1},{idMedrecdoc:'B',status:'pending'}]}),
    onTask: () => {}, onItems: items => { rows = applyQueryItems(rows, items) } })
  assert.equal(rows[0].score, 88)
  assert.equal(rows[0].highSeverityCount, 1)
  assert.equal(rows[1].qcStatus, 'failed')
  assert.equal(rows[1].score, null)
})

test('malformed or cross-batch progress cannot mark current rows successful', async () => {
  for (const items of [[], [{idMedrecdoc:'OTHER',status:'succeeded'}]]) {
    await assert.rejects(runPageQuery({ records:[{idMedrecdoc:'A'}],isCurrent:()=>true,
      createTask:async()=>({taskId:'T'}),fetchTask:async()=>({status:'completed',items}),
      onTask:()=>{},onItems:()=>assert.fail('invalid progress was displayed') }), /进度不完整/)
  }
})
