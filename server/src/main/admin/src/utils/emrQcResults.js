const severityOrder = { high: 0, medium: 1, low: 2, info: 3 }

const sectionAliasGroups = [
  ['患者基础信息', '基本信息', '一般信息', '页眉信息'],
  ['主诉'],
  ['现病史'],
  ['既往史', '个人史', '家族史', '月经史', '婚育史'],
  ['查体与辅助检查', '体格检查', '专科检查', '检验检查', '辅助检查'],
  ['诊断', '门急诊诊断', '门诊诊断', '中医诊断', '西医诊断'],
  ['诊疗措施', '处理', '处置', '治疗'],
  ['病历书写基本要求', '签名信息'],
  ['其他', '注意事项']
]

export function normalizeEmrText(value) {
  return String(value || '').toLowerCase().replace(/[\s\p{P}\p{S}]/gu, '')
}

export function findingLocation(finding) {
  const current = finding || {}
  const category = String(current.category || '').trim()
  const section = String(current.sectionName || '').trim()
  const generic = !section || /^(全文|整份病历|全篇)$/.test(section)
  if (!category) return generic ? '未分类' : section
  if (generic || normalizeEmrText(category) === normalizeEmrText(section)) return category
  return `${category} · ${section}`
}

function escapeHtmlAttribute(value) {
  return String(value || '').replace(/&/g, '&amp;').replace(/"/g, '&quot;')
    .replace(/</g, '&lt;').replace(/>/g, '&gt;')
}

export function decorateEmrHtmlPreview(html, sectionLabel) {
  const source = String(html || '')
  const label = String(sectionLabel || '').replace(/[\s：:]+$/u, '').trim()
  if (!source || !label) return source
  const marker = `data-emr-section="${escapeHtmlAttribute(label)}"`
  if (!source.includes(marker)) return source
  return source.replace(marker, `${marker} data-emr-linked="true"`)
}

export function findingUsesStructuredPreview(htmlPreview) {
  return !String(htmlPreview || '').trim()
}

function matchingAliasGroup(value) {
  const normalized = normalizeEmrText(value)
  if (!normalized) return []
  return sectionAliasGroups.find(group => group.some(alias => {
    const target = normalizeEmrText(alias)
    return normalized === target || normalized.includes(target) || target.includes(normalized)
  })) || []
}

function excerptTokens(value) {
  const full = normalizeEmrText(value)
  const tokens = String(value || '').split(/[\n。；;…]+/)
    .map(normalizeEmrText).filter(item => item.length >= 4)
  if (full.length >= 4) tokens.push(full)
  return [...new Set(tokens)].sort((a, b) => b.length - a.length)
}

function fieldForExcerpt(section, tokens) {
  const fields = section && Array.isArray(section.fields) ? section.fields : []
  return fields.findIndex(field => {
    const value = normalizeEmrText(`${field.name || ''}${field.value || ''}`)
    return tokens.some(token => value.includes(token) || (value.length >= 8 && token.includes(value)))
  })
}

function sectionForName(sections, name) {
  const requested = normalizeEmrText(name)
  if (!requested) return -1
  let index = sections.findIndex(section => normalizeEmrText(section.name) === requested)
  if (index >= 0) return index
  index = sections.findIndex(section => {
    const candidate = normalizeEmrText(section.name)
    return candidate && (candidate.includes(requested) || requested.includes(candidate))
  })
  if (index >= 0) return index
  const aliases = matchingAliasGroup(name).map(normalizeEmrText)
  return sections.findIndex(section => {
    const candidate = normalizeEmrText(section.name)
    return aliases.some(alias => candidate === alias || candidate.includes(alias) || alias.includes(candidate))
  })
}

export function resolveFindingTarget(sections, finding) {
  const available = Array.isArray(sections) ? sections : []
  const current = finding || {}
  const sectionName = String(current.sectionName || '').trim()
  const category = String(current.category || '').trim()
  const genericSection = !sectionName || /^(全文|整份病历|全篇)$/.test(sectionName)
  const names = []
  if (!genericSection) names.push(sectionName)
  if (category && category !== sectionName) names.push(category)
  for (const match of String(current.issue || '').matchAll(/[「『“"]([^」』”"]{1,24})[」』”"]/g)) names.push(match[1])
  const tokens = excerptTokens(current.excerpt)

  for (const name of names) {
    const sectionIndex = sectionForName(available, name)
    if (sectionIndex < 0) continue
    const fieldIndex = fieldForExcerpt(available[sectionIndex], tokens)
    return { scope: 'section', sectionIndex, fieldIndex, label: available[sectionIndex].name, matchedBy: fieldIndex >= 0 ? 'excerpt' : 'section' }
  }

  if (tokens.length) {
    for (let sectionIndex = 0; sectionIndex < available.length; sectionIndex++) {
      const fieldIndex = fieldForExcerpt(available[sectionIndex], tokens)
      if (fieldIndex >= 0) return { scope: 'section', sectionIndex, fieldIndex, label: available[sectionIndex].name, matchedBy: 'excerpt' }
    }
  }

  const requested = names[0] || (!genericSection ? sectionName : '')
  if (matchingAliasGroup(requested || category).some(alias => alias === '患者基础信息')) {
    return { scope: 'meta', sectionIndex: -1, fieldIndex: -1, label: '患者基础信息', matchedBy: 'category' }
  }
  if (requested) return { scope: 'missing', sectionIndex: -1, fieldIndex: -1, label: requested, matchedBy: 'missing' }
  return { scope: 'record', sectionIndex: -1, fieldIndex: -1, label: '整份病历', matchedBy: 'record' }
}

export function departmentOptions(doctors) {
  return [...new Set((doctors || []).map(item => (item.deptName || '').trim()).filter(Boolean))]
    .sort((a, b) => a.localeCompare(b, 'zh-CN'))
}

export function doctorsForDepartment(doctors, department) {
  return (doctors || []).filter(item => !department || (item.deptName || '').trim() === department)
}

export function orderedFindings(qc, severity = 'all') {
  const findings = qc && Array.isArray(qc.findings) ? qc.findings : []
  return findings.map((finding, index) => ({ ...finding, originalIndex: index }))
    .filter(finding => severity === 'all' || finding.severity === severity)
    .sort((a, b) => (severityOrder[a.severity] ?? 4) - (severityOrder[b.severity] ?? 4) || a.originalIndex - b.originalIndex)
}

export function dimensionPercent(score, weight = 100) {
  const value = Number(score)
  const max = Number(weight)
  if (!Number.isFinite(value) || !Number.isFinite(max) || max <= 0) return 0
  return Math.max(0, Math.min(100, (value / max) * 100))
}

export function totalWeight(qc) {
  const dimensions = qc && Array.isArray(qc.dimensions) ? qc.dimensions : []
  const total = dimensions.reduce((sum, item) => sum + (Number(item.weight) || 0), 0)
  return total > 0 ? total : 100
}

export function totalScore(qc) {
  const dimensions = qc && Array.isArray(qc.dimensions) ? qc.dimensions : []
  return dimensions.reduce((sum, item) => sum + (Number(item.score) || 0), 0)
}

export function findingCounts(findings) {
  const active = (Array.isArray(findings) ? findings : []).filter(item => !item.ignored)
  return {
    total: active.length,
    high: active.filter(item => item.severity === 'high').length
  }
}

function dimensionByCategory(dimensions, category) {
  return (Array.isArray(dimensions) ? dimensions : [])
    .find(item => item.code === category || item.name === category)
}

function boundedInteger(value, weight) {
  const number = Number(value)
  const max = Number(weight)
  if (!Number.isFinite(number) || !Number.isFinite(max) || max <= 0) return 0
  return Math.min(Math.floor(max), Math.max(0, Math.round(number)))
}

export function recalculateScores(dimensions, findings) {
  for (const dimension of dimensions) {
    const deducted = findings.filter(item => !item.ignored && dimensionByCategory([dimension], item.category))
      .reduce((sum, item) => sum + boundedInteger(item.deduction, dimension.weight), 0)
    dimension.score = Math.max(0, dimension.weight - deducted)
  }
}

export function updateFindingDeduction(dimensions, finding, value, findings = [finding]) {
  const dimension = dimensionByCategory(dimensions, finding && finding.category)
  if (!finding || !dimension) return
  finding.deduction = boundedInteger(value, dimension.weight)
  recalculateScores(dimensions, findings)
}

export function setFindingIgnored(dimensions, finding, ignored, findings = [finding]) {
  if (!finding) return
  finding.ignored = Boolean(ignored)
  recalculateScores(dimensions, findings)
}

export function setFindingCategory(dimensions, finding, category, findings = [finding]) {
  const dimension = dimensionByCategory(dimensions, category)
  if (!finding || !dimension) return
  finding.category = dimension.name
  finding.deduction = boundedInteger(finding.deduction, dimension.weight)
  recalculateScores(dimensions, findings)
}
