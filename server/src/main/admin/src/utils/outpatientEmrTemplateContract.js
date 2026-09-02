export const recordFieldOptions = Object.freeze([
  { label: '主诉', value: 'chiefComplaint' },
  { label: '现病史', value: 'historyOfPresentIllness' },
  { label: '既往史', value: 'pastMedicalHistory' },
  { label: '个人史', value: 'personalHistory' },
  { label: '月经史', value: 'menstrualHistory' },
  { label: '家族史', value: 'familyHistory' },
  { label: '体格检查', value: 'physicalExam' },
  { label: '注意事项', value: 'precautions' }
])

export const mappingSourceLabels = Object.freeze({
  'definition-record-field': '模板字段声明',
  'definition-article-record-field': '模板章节声明',
  'canonical-id': '标准字段 ID',
  'deterministic-alias': '字段别名',
  'deterministic-article': '章节语义',
  'unmapped': '未识别'
})

const recordFields = new Set(recordFieldOptions.map(item => item.value))
const mappingSources = new Set(Object.keys(mappingSourceLabels))
const countFields = [
  'fieldCount',
  'writableFieldCount',
  'dictionaryFieldCount',
  'mappedFieldCount'
]

function isExactText(value, allowEmpty = false) {
  return typeof value === 'string' &&
    (allowEmpty || value.length > 0) &&
    value === value.trim()
}

function isNullableExactText(value) {
  return value === null || isExactText(value)
}

function isSnapshotSummary(value) {
  return Boolean(
    value &&
    isExactText(value.id) &&
    isExactText(value.idOrg) &&
    isNullableExactText(value.idRegion) &&
    isExactText(value.templateId) &&
    isExactText(value.templateName) &&
    typeof value.templateHash === 'string' && /^[a-f0-9]{64}$/.test(value.templateHash) &&
    isExactText(value.idDevice) &&
    isNullableExactText(value.cdDevice) &&
    Number.isInteger(value.receivedAt) &&
    Number.isInteger(value.createdAt) &&
    Number.isInteger(value.updatedAt) &&
    countFields.every(field => Number.isInteger(value[field]) && value[field] >= 0) &&
    value.fieldCount > 0 &&
    value.writableFieldCount <= value.fieldCount &&
    value.dictionaryFieldCount <= value.fieldCount &&
    value.mappedFieldCount <= value.fieldCount
  )
}

export function requireSnapshotPage(data) {
  if (
    !data ||
    !Number.isInteger(data.current) ||
    data.current < 1 ||
    !Number.isInteger(data.size) ||
    data.size < 1 ||
    !Array.isArray(data.records) ||
    !data.records.every(isSnapshotSummary) ||
    !Number.isInteger(data.total) ||
    data.total < 0
  ) {
    throw new Error('门诊病例模板响应不符合当前接口协议')
  }
  return data
}

function isDictionaryItems(items) {
  if (!Array.isArray(items)) return false
  const tokens = new Set()
  return items.every(item => {
    if (!item || !isExactText(item.value, true) || !isExactText(item.text)) return false
    const itemTokens = item.value === item.text ? [item.value] : [item.value, item.text]
    if (itemTokens.some(token => tokens.has(token))) return false
    itemTokens.forEach(token => tokens.add(token))
    return true
  })
}

function isSnapshotField(field) {
  if (
    !field ||
    !isExactText(field.id) ||
    !isExactText(field.name) ||
    !isExactText(field.type) ||
    !isExactText(field.articleTemplateId) ||
    !isExactText(field.articleId) ||
    !isExactText(field.articleName) ||
    !isExactText(field.articleDefinitionName) ||
    typeof field.readonly !== 'boolean' ||
    typeof field.aiSuitable !== 'boolean' ||
    !isExactText(field.baselineValue, true) ||
    !isExactText(field.baselineDictionaryValue, true) ||
    !isDictionaryItems(field.dictionaryItems) ||
    !isNullableExactText(field.recordField) ||
    !mappingSources.has(field.mappingSource) ||
    !isNullableExactText(field.projectionMode)
  ) return false

  if (field.recordField === null) {
    return field.mappingSource === 'unmapped' && field.projectionMode === null
  }
  return recordFields.has(field.recordField) &&
    field.mappingSource !== 'unmapped' &&
    (field.projectionMode === 'direct' || field.projectionMode === 'section-compose')
}

function isMappingOverride(value) {
  if (
    !value ||
    !isExactText(value.fieldId) ||
    (value.mappingStatus !== 'mapped' && value.mappingStatus !== 'unmapped') ||
    !isNullableExactText(value.recordField) ||
    !isNullableExactText(value.projectionMode) ||
    !isNullableExactText(value.automaticRecordField) ||
    !mappingSources.has(value.automaticMappingSource) ||
    !isNullableExactText(value.automaticProjectionMode) ||
    (value.updatedAt !== null && value.updatedAt !== undefined && !Number.isInteger(value.updatedAt))
  ) return false

  if (value.mappingStatus === 'unmapped') {
    return value.recordField === null && value.projectionMode === null
  }
  return recordFields.has(value.recordField) &&
    (value.projectionMode === 'direct' || value.projectionMode === 'section-compose')
}

export function requireSnapshotDetail(data) {
  const mappingOverrides = data && data.mappingOverrides === undefined ? [] : data && data.mappingOverrides
  if (
    !isSnapshotSummary(data) ||
    typeof data.templateHtml !== 'string' ||
    !data.templateHtml.trim() ||
    typeof data.templateDefinition !== 'string' ||
    !data.templateDefinition.trim() ||
    !data.parseResult ||
    data.parseResult.schemaVersion !== 'outpatient-emr-template-pair.v1' ||
    !Array.isArray(data.parseResult.fields) ||
    data.parseResult.fields.length !== data.fieldCount ||
    !data.parseResult.fields.every(isSnapshotField) ||
    new Set(data.parseResult.fields.map(field => field.id)).size !== data.fieldCount ||
    data.parseResult.fields.filter(field => !field.readonly).length !== data.writableFieldCount ||
    data.parseResult.fields.filter(field => field.dictionaryItems.length > 0).length !== data.dictionaryFieldCount ||
    data.parseResult.fields.filter(field => field.recordField !== null).length !== data.mappedFieldCount ||
    !Array.isArray(mappingOverrides) ||
    !mappingOverrides.every(isMappingOverride) ||
    new Set(mappingOverrides.map(item => item.fieldId)).size !== mappingOverrides.length ||
    mappingOverrides.some(item => !data.parseResult.fields.some(field => field.id === item.fieldId))
  ) {
    throw new Error('门诊病例模板详情响应不符合当前接口协议')
  }
  return { ...data, mappingOverrides }
}
