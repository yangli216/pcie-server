const SAMPLE_RECORD_VALUES = Object.freeze({
  chiefComplaint: '咳嗽、咳痰伴发热3天。',
  historyOfPresentIllness: '患者3天前受凉后出现咳嗽、咳白色黏痰，伴发热，最高体温38.5℃，无胸痛、气促及咯血，未自行服药，今日来诊。',
  pastMedicalHistory: '既往体健，否认高血压、糖尿病等慢性病史，否认药物及食物过敏史。',
  personalHistory: '无吸烟、饮酒史，无疫区及有害物质接触史。',
  menstrualHistory: '月经规律，末次月经时间已由患者确认。',
  maritalReproductiveHistory: '已婚已育，否认当前妊娠。',
  familyHistory: '否认家族遗传性疾病及类似疾病史。',
  physicalExam: 'T 38.1℃，P 92次/分，R 20次/分，BP 128/76mmHg；神志清，咽部充血，双肺呼吸音粗，右下肺可闻及少量湿啰音。',
  precautions: '建议清淡饮食、充分休息并监测体温；如持续高热、气促或症状加重，请及时复诊。'
})

const SAMPLE_TEMPLATE_HTML = `
<article style="max-width: 840px; margin: 0 auto; color: #27363b; font-family: 'Noto Serif SC', 'Songti SC', SimSun, serif; line-height: 1.85;">
  <header style="text-align: center; border-bottom: 2px solid #34484f; padding-bottom: 16px; margin-bottom: 18px;">
    <div style="font-size: 17px; letter-spacing: 4px;">全医慧助示例医院</div>
    <h1 style="margin: 6px 0 0; font-size: 28px; letter-spacing: 8px;">门诊病历</h1>
  </header>
  <section data-id="article-header" data-article="header" data-name="基本信息">
    <table style="width: 100%; border-collapse: collapse; margin-bottom: 18px; font-size: 15px;">
      <tbody>
        <tr>
          <td style="padding: 6px; border-bottom: 1px solid #cdd7da;">姓名：<span data-id="patientName" data-name="患者姓名" data-type="text" data-readonly="true"><span class="tag-value"></span></span></td>
          <td style="padding: 6px; border-bottom: 1px solid #cdd7da;">性别：<span data-id="patientGender" data-name="患者性别" data-type="text" data-readonly="true"><span class="tag-value"></span></span></td>
          <td style="padding: 6px; border-bottom: 1px solid #cdd7da;">年龄：<span data-id="patientAge" data-name="患者年龄" data-type="text" data-readonly="true"><span class="tag-value"></span></span></td>
          <td style="padding: 6px; border-bottom: 1px solid #cdd7da;">科室：<span data-id="departmentName" data-name="就诊科室" data-type="text" data-readonly="true"><span class="tag-value"></span></span></td>
        </tr>
        <tr>
          <td colspan="2" style="padding: 6px; border-bottom: 1px solid #cdd7da;">门诊号：<span data-id="outpatientNo" data-name="门诊号" data-type="text" data-readonly="true"><span class="tag-value"></span></span></td>
          <td colspan="2" style="padding: 6px; border-bottom: 1px solid #cdd7da;">就诊时间：<span data-id="visitTime" data-name="就诊时间" data-type="datetime" data-readonly="true"><span class="tag-value"></span></span></td>
        </tr>
      </tbody>
    </table>
  </section>
  <section data-id="article-chief" data-article="chiefComplaint" data-name="主诉">
    <p><strong>主诉：</strong><span data-id="chiefComplaint" data-name="主诉" data-type="text" data-readonly="false" data-ai-suitable="true" data-record-field="chiefComplaint"><span class="tag-value"></span></span></p>
  </section>
  <section data-id="article-present" data-article="historyOfPresentIllness" data-name="现病史">
    <p><strong>现病史：</strong><span data-id="historyOfPresentIllness" data-name="现病史" data-type="textarea" data-readonly="false" data-ai-suitable="true" data-record-field="historyOfPresentIllness"><span class="tag-value"></span></span></p>
  </section>
  <section data-id="article-past" data-article="pastMedicalHistory" data-name="既往史">
    <p><strong>既往史：</strong><span data-id="pastMedicalHistory" data-name="既往史" data-type="textarea" data-readonly="false" data-ai-suitable="true" data-record-field="pastMedicalHistory"><span class="tag-value"></span></span></p>
  </section>
  <section data-id="article-physical" data-article="physicalExam" data-name="体格检查">
    <p><strong>体格检查：</strong><span data-id="physicalExam" data-name="体格检查" data-type="textarea" data-readonly="false" data-ai-suitable="true" data-record-field="physicalExam"><span class="tag-value"></span></span></p>
  </section>
  <section data-id="article-diagnosis" data-article="diagnosis" data-name="初步诊断">
    <p><strong>初步诊断：</strong><span data-id="primaryDiagnosis" data-name="初步诊断" data-type="text" data-readonly="true"><span class="tag-value"></span></span></p>
  </section>
  <section data-id="article-precautions" data-article="precautions" data-name="注意事项">
    <p><strong>注意事项：</strong><span data-id="precautions" data-name="注意事项" data-type="textarea" data-readonly="false" data-ai-suitable="true" data-record-field="precautions"><span class="tag-value"></span></span></p>
  </section>
  <footer style="display: flex; justify-content: flex-end; gap: 34px; margin-top: 28px; font-size: 15px;">
    <span>记录医师：<span data-id="doctorName" data-name="记录医师" data-type="text" data-readonly="true"><span class="tag-value"></span></span></span>
    <span>医师签名：<span data-id="doctorSignature" data-name="医师签名" data-type="signature" data-readonly="true"><span class="tag-value"></span></span></span>
  </footer>
</article>`

function previewField(id, name, options = {}) {
  return {
    id,
    name,
    type: options.type || 'text',
    articleTemplateId: options.articleTemplateId || 'article-header',
    articleId: options.articleId || 'header',
    articleName: options.articleName || '基本信息',
    articleDefinitionName: options.articleName || '基本信息',
    readonly: options.readonly !== false,
    aiSuitable: Boolean(options.aiSuitable),
    baselineValue: options.baselineValue || '',
    baselineDictionaryValue: '',
    dictionaryItems: options.dictionaryItems || [],
    recordField: options.recordField || null,
    mappingSource: options.recordField ? 'canonical-id' : 'unmapped',
    projectionMode: options.recordField ? 'direct' : null
  }
}

const SAMPLE_FIELDS = [
  previewField('patientName', '患者姓名'),
  previewField('patientGender', '患者性别', { baselineValue: '女' }),
  previewField('patientAge', '患者年龄', { baselineValue: '36岁' }),
  previewField('departmentName', '就诊科室'),
  previewField('outpatientNo', '门诊号'),
  previewField('visitTime', '就诊时间', { type: 'datetime' }),
  previewField('chiefComplaint', '主诉', { articleTemplateId: 'article-chief', articleId: 'chiefComplaint', articleName: '主诉', readonly: false, aiSuitable: true, recordField: 'chiefComplaint' }),
  previewField('historyOfPresentIllness', '现病史', { type: 'textarea', articleTemplateId: 'article-present', articleId: 'historyOfPresentIllness', articleName: '现病史', readonly: false, aiSuitable: true, recordField: 'historyOfPresentIllness' }),
  previewField('pastMedicalHistory', '既往史', { type: 'textarea', articleTemplateId: 'article-past', articleId: 'pastMedicalHistory', articleName: '既往史', readonly: false, aiSuitable: true, recordField: 'pastMedicalHistory' }),
  previewField('physicalExam', '体格检查', { type: 'textarea', articleTemplateId: 'article-physical', articleId: 'physicalExam', articleName: '体格检查', readonly: false, aiSuitable: true, recordField: 'physicalExam' }),
  previewField('primaryDiagnosis', '初步诊断', { articleTemplateId: 'article-diagnosis', articleId: 'diagnosis', articleName: '初步诊断' }),
  previewField('precautions', '注意事项', { type: 'textarea', articleTemplateId: 'article-precautions', articleId: 'precautions', articleName: '注意事项', readonly: false, aiSuitable: true, recordField: 'precautions' }),
  previewField('doctorName', '记录医师'),
  previewField('doctorSignature', '医师签名', { type: 'signature' })
]

export const outpatientPreviewExample = {
  id: 'outpatient-preview-example',
  idOrg: 'ORG001',
  idRegion: null,
  idDevice: 'preview-example-device',
  cdDevice: 'SIM-OUTPATIENT-PREVIEW',
  templateId: 'SIM-OUTPATIENT-RECORD-PREVIEW',
  templateName: '模拟门诊病例－门诊病历',
  templateHash: '0'.repeat(64),
  templateHtml: SAMPLE_TEMPLATE_HTML,
  templateDefinition: JSON.stringify({ schemaVersion: 'outpatient-emr-template-definition.preview', articles: [] }, null, 2),
  parseResult: {
    schemaVersion: 'outpatient-emr-template-pair.v1',
    fields: SAMPLE_FIELDS
  },
  mappingOverrides: [],
  fieldCount: SAMPLE_FIELDS.length,
  writableFieldCount: SAMPLE_FIELDS.filter(field => !field.readonly).length,
  dictionaryFieldCount: 0,
  mappedFieldCount: SAMPLE_FIELDS.filter(field => field.recordField).length,
  receivedAt: 1788138000000,
  createdAt: 1788138000000,
  updatedAt: 1788138000000
}

function normalizeText(value) {
  return String(value || '').replace(/\s+/g, ' ').trim()
}

function includesAny(text, keywords) {
  return keywords.some(keyword => text.includes(keyword))
}

function resolveDictionaryValue(field) {
  const items = Array.isArray(field.dictionaryItems) ? field.dictionaryItems : []
  if (items.length === 0) return ''
  const baseline = normalizeText(field.baselineValue || field.baselineDictionaryValue)
  const baselineItem = items.find(item => item.text === baseline || item.value === baseline)
  if (baselineItem) return baselineItem.text
  const negativeItem = items.find(item => includesAny(item.text, ['否认', '无', '未见', '正常']))
  return negativeItem ? negativeItem.text : items[0].text
}

function sectionComposeValue(field) {
  const text = `${normalizeText(field.id)} ${normalizeText(field.name)}`.toLowerCase()
  const dictionaryValue = resolveDictionaryValue(field)
  if (dictionaryValue) return dictionaryValue
  if (includesAny(text, ['体温'])) return '38.1'
  if (includesAny(text, ['呼吸'])) return '20'
  if (includesAny(text, ['脉搏', '心率'])) return '92'
  if (includesAny(text, ['收缩压'])) return '128'
  if (includesAny(text, ['舒张压'])) return '76'
  if (includesAny(text, ['末次月经'])) return '2026-08-10'
  if (includesAny(text, ['专科检查'])) return '咽部充血，右下肺可闻及少量湿啰音。'
  if (includesAny(text, ['辅助检查'])) return '待完善血常规、炎症指标及胸部影像检查。'
  if (includesAny(text, ['吸烟'])) return '否认'
  if (includesAny(text, ['饮酒'])) return '否认'
  if (includesAny(text, ['过敏'])) return '否认'
  if (includesAny(text, ['手术', '外伤', '输血', '传染'])) return '否认'
  return normalizeText(field.baselineValue) || '未见明显异常'
}

function sampleValueForField(field) {
  if (field.recordField && SAMPLE_RECORD_VALUES[field.recordField]) {
    return field.projectionMode === 'section-compose'
      ? sectionComposeValue(field)
      : SAMPLE_RECORD_VALUES[field.recordField]
  }

  const text = `${normalizeText(field.id)} ${normalizeText(field.name)} ${normalizeText(field.articleName)}`.toLowerCase()
  const dictionaryValue = resolveDictionaryValue(field)
  if (dictionaryValue) return dictionaryValue
  if (includesAny(text, ['医疗机构', '医院名称', '机构名称'])) return '全医慧助示例医院'
  if (includesAny(text, ['患者姓名', '姓名', 'patientname'])) return '李某'
  if (includesAny(text, ['性别', 'gender'])) return '女'
  if (includesAny(text, ['年龄', 'age'])) return '36岁'
  if (includesAny(text, ['科室', 'department'])) return '全科医学科'
  if (includesAny(text, ['门诊号', '就诊号', '病历号', 'outpatientno'])) return 'MZ20260831001'
  if (includesAny(text, ['就诊时间', '记录时间', '签名时间', 'visittime'])) return '2026-08-31 09:30'
  if (includesAny(text, ['初步诊断', '临床诊断', '主要诊断', '目前诊断', 'primarydiagnosis'])) return '社区获得性肺炎（待排）'
  if (includesAny(text, ['专科检查'])) return '咽部充血，右下肺可闻及少量湿啰音。'
  if (includesAny(text, ['辅助检查'])) return '待完善血常规、炎症指标及胸部影像检查。'
  if (includesAny(text, ['医嘱'])) return '完善相关检查，结合结果给予对症治疗。'
  if (includesAny(text, ['记录医师', '医师签名', '医生签名', 'doctor'])) return '王医生'
  return normalizeText(field.baselineValue)
}

function removeActiveContent(doc) {
  doc.querySelectorAll('script, iframe, object, embed, form, base, link, meta').forEach(node => node.remove())
  doc.querySelectorAll('img[src]').forEach(node => node.removeAttribute('src'))
  doc.querySelectorAll('*').forEach((node) => {
    Array.from(node.attributes || []).forEach((attribute) => {
      if (/^on/i.test(attribute.name)) node.removeAttribute(attribute.name)
    })
  })
}

// Walk adjacent inline text only within this clause. Another control or paragraph ends a suffix.
function fixedSuffix(slot) {
  const root = slot.closest('p,li,td,.fragment,[data-article]') || slot.parentElement
  if (!root) return ''
  const walker = slot.ownerDocument.createTreeWalker(root, 5)
  walker.currentNode = slot
  let node
  while ((node = walker.nextNode())) {
    if (slot.contains(node)) continue
    if (node.nodeType === 1 && node.matches('[data-type],br,p,li,td,[data-article]')) break
    if (node.nodeType === 3 && node.textContent.trim()) return node.textContent.trim()
  }
  return ''
}

export function buildOutpatientPreviewDocument(templateHtml, fields = []) {
  const doc = new DOMParser().parseFromString(templateHtml || '', 'text/html')
  removeActiveContent(doc)

  const fieldMap = new Map((Array.isArray(fields) ? fields : []).map(field => [field.id, field]))
  Array.from(doc.querySelectorAll('[data-id][data-type]')).forEach((node) => {
    const id = node.getAttribute('data-id') || ''
    if (fieldMap.get(id)?.visible === false) { node.remove(); return }
    const field = fieldMap.get(id) || {
      id,
      name: node.getAttribute('data-name') || node.getAttribute('title') || id,
      articleName: node.closest('[data-article]')?.getAttribute('data-name') || '',
      readonly: node.getAttribute('data-readonly') !== 'false',
      aiSuitable: node.getAttribute('data-ai-suitable') === 'true',
      baselineValue: normalizeText(node.textContent),
      dictionaryItems: [],
      recordField: node.getAttribute('data-record-field'),
      projectionMode: node.getAttribute('data-record-field') ? 'direct' : null
    }
    const valueNode = node.matches('.tag-value') ? node : (node.querySelector('.tag-value') || node)
    valueNode.textContent = syntheticSlotValue(field, fixedSuffix(node))
    valueNode.setAttribute('contenteditable', 'false')
    valueNode.setAttribute('data-outpatient-emr-field-id', id)
    valueNode.classList.add('outpatient-emr-field')
    valueNode.classList.add(field.aiSuitable && !field.readonly ? 'outpatient-emr-field--ai' : 'outpatient-emr-field--readonly')
  })

  return `<!doctype html>
<html lang="zh-CN">
<head>
  <meta charset="utf-8">
  <meta http-equiv="Content-Security-Policy" content="default-src 'none'; style-src 'unsafe-inline'; img-src data:; font-src data:">
  <meta name="viewport" content="width=device-width, initial-scale=1">
  <style>
    * { box-sizing: border-box; }
    html { background: #f3f7f6; }
    body { margin: 0; padding: 28px; color: #253238; background: #f3f7f6; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", "Microsoft YaHei", sans-serif; }
    body > * { background: #fff; }
    .outpatient-emr-field { border-radius: 3px; transition: background-color .16s ease, box-shadow .16s ease; white-space: pre-wrap; }
    .outpatient-emr-field--ai { padding: 1px 3px; background: #e9f8f2; box-shadow: inset 0 -1px 0 #63b89a; }
    .outpatient-emr-field--readonly { padding: 1px 2px; background: #f1f4f5; }
  </style>
</head>
<body>${doc.body.innerHTML}</body>
</html>`
}

/** Replace every source slot with its label before showing a synthetic composition. */
export function fieldClausePreview(html, fields, current, mapping) {
  if (!current) return { sentence: '', result: '', parts: [] }
  const doc = new DOMParser().parseFromString(html || '', 'text/html')
  removeActiveContent(doc)
  const slot = Array.from(doc.querySelectorAll('[data-id][data-type]')).find(node =>
    node.getAttribute('data-id') === current.id && node.closest('[data-article]')?.getAttribute('data-id') === current.articleTemplateId)
  if (!slot) return { sentence: '', result: '', parts: [] }
  const block = slot.closest('p,li,td,.fragment,[data-article]') || slot.parentElement
  const byId = new Map(fields.map(f => [f.id, f]))
  const parts = []
  function visit(node) {
    if (node.nodeType === 3) {
      if (node.textContent.trim()) parts.push({ text: node.textContent.trim() })
      return
    }
    if (node.nodeType !== 1 || node.matches('script,style,button,[hidden],[aria-hidden="true"],.tag-marker,.tag-article-title,.placeholder')) return
    if (node.hasAttribute('data-type') && node.hasAttribute('data-id')) {
      const field = byId.get(node.getAttribute('data-id'))
      if (field) parts.push({ field })
      return
    }
    Array.from(node.childNodes).forEach(visit)
  }
  visit(block)
  const tokens = parts.map(part => part.field ? { id: part.field.id, text: '〔' + part.field.name + '〕' } : { text: part.text })
  const sentence = tokens.map(token => token.text).join('')
  const bySlot = new Map(Array.from(doc.querySelectorAll('[data-id][data-type]')).map(node => [node.getAttribute('data-id'), node]))
  const renderedParts = parts.map(part => part.field
    ? { label: part.field.name, value: syntheticSlotValue(part.field, fixedSuffix(bySlot.get(part.field.id))) }
    : { label: '模板固定字', value: part.text })
  const preview = { sentence, tokens, parts: renderedParts, result: renderedParts.map(part => part.value).join(''), standard: '' }
  if (mapping.mappingStatus !== 'mapped') return { ...preview, note: '本栏仅返回原始字段值。' }
  if (!mapping.recordField) return preview
  const composedFields = fields.filter(f => !f.readonly && (f.id === current.id || f.recordField === mapping.recordField))
  if (composedFields.length > 1 && !composedFields.every(f => f.articleId && f.articleId === composedFields[0].articleId
    && (f.id === current.id ? mapping.projectionMode : f.projectionMode) === 'section-compose')) {
    return { ...preview, note: '标准段映射冲突，请核对' + composedFields.map(f => '「' + f.name + '」').join('、') + '。' }
  }
  const standard = composedFields.flatMap(f => {
    const node = bySlot.get(f.id)
    if (!node) return []
    const value = syntheticSlotValue(f, fixedSuffix(node))
    if (!value) return []
    const name = f.name.replace(/标志$/, '').trim() || f.name
    return [mapping.projectionMode === 'direct' || value.startsWith(name + '：') || value.startsWith(name + ':') ? value : name + '：' + value]
  }).join('；')
  return { ...preview, standard }
}

function syntheticSlotValue(field, suffix) {
  if (field.dictionaryItems?.length) return field.dictionaryItems[0].text
  if (/持续时间|时长|duration/i.test(field.name + field.id)) return /^\s*(天|日|周|月|年|小时|分钟)/.test(suffix) || field.type === 'number' ? '3' : '3天'
  if (/症状名称|chiefSymptom/i.test(field.name + field.id)) return '咳嗽'
  if (field.recordField === 'chiefComplaint' && field.projectionMode === 'direct') return '咳嗽3天'
  if (field.type === 'number') return '3'
  return sampleValueForField({ ...field, baselineValue: '', baselineDictionaryValue: '' })
}
