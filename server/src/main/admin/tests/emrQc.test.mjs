import assert from 'node:assert/strict'
import test from 'node:test'
import { isWithinOneCalendarYear, oneYearAfter } from '../src/utils/emrQcDateRange.js'
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
