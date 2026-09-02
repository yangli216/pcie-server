const SAMPLE_TIME = '2026-08-31 09:30'

const SAMPLE_TEMPLATE_HTML = `
<article style="max-width: 820px; margin: 0 auto; color: #253238; font-family: 'Noto Serif SC', 'Songti SC', SimSun, serif; line-height: 1.8;">
  <header style="text-align: center; border-bottom: 2px solid #34484f; padding-bottom: 18px; margin-bottom: 20px;">
    <div data-id="页眉医疗机构名称" data-type="text" style="font-size: 17px; letter-spacing: 4px;"><span class="tag-value"></span></div>
    <h1 style="margin: 6px 0 0; font-size: 28px; letter-spacing: 8px;">首次病程记录</h1>
  </header>
  <table style="width: 100%; border-collapse: collapse; margin-bottom: 22px; font-size: 15px;">
    <tbody>
      <tr>
        <td style="padding: 7px 8px; border-bottom: 1px solid #ccd7da;">姓名：<span data-id="页眉姓名" data-type="text"><span class="tag-value"></span></span></td>
        <td style="padding: 7px 8px; border-bottom: 1px solid #ccd7da;">科室：<span data-id="页眉科室名称" data-type="text"><span class="tag-value"></span></span></td>
        <td style="padding: 7px 8px; border-bottom: 1px solid #ccd7da;">床号：<span data-id="页眉床位号" data-type="text"><span class="tag-value"></span></span></td>
        <td style="padding: 7px 8px; border-bottom: 1px solid #ccd7da;">住院号：<span data-id="页眉住院号" data-type="text"><span class="tag-value"></span></span></td>
      </tr>
    </tbody>
  </table>
  <div style="text-align: right; margin-bottom: 14px; font-size: 14px;">
    记录时间：<span data-id="病程记录操作时间" data-type="datetime"><span class="tag-value"></span></span>
  </div>
  <section data-article="首次病程记录" style="font-size: 16px;">
    <p><strong>主诉：</strong><span data-id="主诉" data-type="text"><span class="tag-value"></span></span></p>
    <p><strong>现病史：</strong><span data-id="现病史" data-type="textarea"><span class="tag-value"></span></span></p>
    <p><strong>入院诊断：</strong><span data-id="入院诊断" data-type="text"><span class="tag-value"></span></span></p>
    <p><strong>病例特点：</strong><span data-id="病程记录文本" data-type="textarea"><span class="tag-value"></span></span></p>
    <p><strong>诊疗计划：</strong><span data-id="诊疗计划" data-type="textarea"><span class="tag-value"></span></span></p>
  </section>
  <footer style="display: flex; justify-content: flex-end; gap: 34px; margin-top: 30px; font-size: 15px;">
    <span>记录医师：<span data-id="病程记录操作人员" data-type="text"><span class="tag-value"></span></span></span>
    <span>医师签名：<span data-id="医师签名" data-type="signature"><span class="tag-value"></span></span></span>
  </footer>
</article>`

export const inpatientPreviewExample = {
  templateName: '首次病程记录（预渲染示例）',
  templateId: 'preview-example',
  htmlContent: SAMPLE_TEMPLATE_HTML,
  fields: [
    { id: '页眉医疗机构名称', name: '医疗机构名称', meaning: '病历页眉医疗机构', aiSuitable: false },
    { id: '页眉姓名', name: '患者姓名', meaning: '住院患者姓名', aiSuitable: false },
    { id: '页眉科室名称', name: '住院科室', meaning: '当前住院科室', aiSuitable: false },
    { id: '页眉床位号', name: '床号', meaning: '当前床位', aiSuitable: false },
    { id: '页眉住院号', name: '住院号', meaning: '住院登记号', aiSuitable: false },
    { id: '病程记录操作时间', name: '记录时间', meaning: '病程记录时间', aiSuitable: false },
    { id: '主诉', name: '主诉', meaning: '患者本次入院主要症状', aiSuitable: true },
    { id: '现病史', name: '现病史', meaning: '本次疾病发生和发展经过', aiSuitable: true },
    { id: '入院诊断', name: '入院诊断', meaning: '住院登记诊断', aiSuitable: false },
    { id: '病程记录文本', name: '病例特点', meaning: '首次病程记录正文', aiSuitable: true },
    { id: '诊疗计划', name: '诊疗计划', meaning: '入院后的检查与治疗计划', aiSuitable: true },
    { id: '病程记录操作人员', name: '记录医师', meaning: '当前登录医生', aiSuitable: false },
    { id: '医师签名', name: '医师签名', meaning: '医生确认签名', aiSuitable: false }
  ]
}

function normalizeText(value) {
  return String(value || '').replace(/\s+/g, ' ').trim()
}

function includesAny(text, keywords) {
  return keywords.some(keyword => text.includes(keyword))
}

function sampleValueForField(field) {
  const id = normalizeText(field.id)
  const text = `${id} ${normalizeText(field.name)} ${normalizeText(field.meaning)}`.toLowerCase()

  if (includesAny(text, ['医疗机构', '医院名称', '机构名称'])) return '全医慧助示例医院'
  if (includesAny(text, ['操作人员', '记录医师', '查房医师', '主治医师', '医师签名', '医生签名'])) return '王医生'
  if (includesAny(text, ['患者姓名', '页眉姓名']) || (text.includes('姓名') && !text.includes('医师') && !text.includes('医生'))) return '张某'
  if (includesAny(text, ['科室', '病区'])) return '全科医学科'
  if (includesAny(text, ['床位', '床号'])) return '08床'
  if (includesAny(text, ['住院号', '住院登记号', '住院流水'])) return 'ZY20260831001'
  if (includesAny(text, ['记录时间', '操作时间', '业务时间', '日期时间'])) return SAMPLE_TIME
  if (includesAny(text, ['操作名称', '记录名称', '文书名称', '病历标题'])) return '首次病程记录'
  if (includesAny(text, ['主诉', 'chief_complaint'])) return '咳嗽、咳痰伴发热3天。'
  if (includesAny(text, ['现病史', 'present_illness'])) return '患者3天前受凉后出现咳嗽、咳白色黏痰，伴发热，最高体温38.5℃，无胸痛及咯血，为进一步诊治收入院。'
  if (includesAny(text, ['既往史', 'past_history'])) return '既往体健，否认高血压、糖尿病等慢性病史，否认药物及食物过敏史。'
  if (includesAny(text, ['入院诊断', '主要诊断', '临床诊断', '诊断'])) return '社区获得性肺炎'
  if (includesAny(text, ['体格检查', '查体'])) return 'T 38.1℃，P 92次/分，R 20次/分，BP 128/76mmHg；双肺呼吸音粗，右下肺可闻及少量湿啰音。'
  if (includesAny(text, ['诊疗计划', '治疗计划', '处理意见'])) return '完善血常规、炎症指标及胸部影像检查；结合检查结果给予抗感染、止咳化痰等治疗，动态观察体温及呼吸道症状。'
  if (includesAny(text, ['病程记录文本', '病例特点', '记录正文', '病程正文', '查房记录'])) return '患者青年男性，急性起病，以咳嗽、咳痰、发热为主要表现；查体右下肺可闻及少量湿啰音。现有资料支持社区获得性肺炎，需结合影像及实验室检查进一步评估病情。'
  if (includesAny(text, ['入院情况'])) return '患者因咳嗽、咳痰伴发热3天入院，一般情况尚可。'
  if (includesAny(text, ['诊疗经过'])) return '入院后完善相关检查，并给予对症支持治疗，病情变化待持续观察。'
  if (includesAny(text, ['出院情况'])) return '示例字段：请根据真实出院资料审核补充。'
  if (includesAny(text, ['出院医嘱'])) return '示例字段：请根据真实诊疗结果和随访计划审核补充。'
  if (field.aiSuitable) return '示例病例内容：请结合实际住院资料审核补充。'
  return normalizeText(field.defaultValue)
}

function removeActiveContent(doc) {
  doc.querySelectorAll('script, iframe, object, embed, form, base, link, meta').forEach(node => node.remove())
  doc.querySelectorAll('*').forEach((node) => {
    Array.from(node.attributes || []).forEach((attribute) => {
      if (/^on/i.test(attribute.name)) node.removeAttribute(attribute.name)
    })
  })
}

export function buildInpatientPreviewDocument(htmlContent, fields = []) {
  const doc = new DOMParser().parseFromString(htmlContent || '', 'text/html')
  removeActiveContent(doc)

  const fieldMap = new Map((Array.isArray(fields) ? fields : []).map(field => [field.id, field]))
  Array.from(doc.querySelectorAll('[data-id][data-type]')).forEach((node) => {
    const id = node.getAttribute('data-id') || ''
    const field = fieldMap.get(id) || {
      id,
      name: node.getAttribute('data-name') || node.getAttribute('title') || id,
      defaultValue: node.getAttribute('data-default') || normalizeText(node.textContent),
      aiSuitable: false
    }
    const valueNode = node.querySelector('.tag-value') || node
    valueNode.textContent = sampleValueForField(field)
    valueNode.setAttribute('contenteditable', 'false')
    valueNode.setAttribute('data-inpatient-emr-field-id', id)
    valueNode.classList.add('inpatient-emr-field')
    valueNode.classList.add(field.aiSuitable ? 'inpatient-emr-field--ai' : 'inpatient-emr-field--readonly')
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
    .inpatient-emr-field { border-radius: 3px; transition: background-color .16s ease, box-shadow .16s ease; }
    .inpatient-emr-field--ai { padding: 1px 3px; background: #e9f8f2; box-shadow: inset 0 -1px 0 #63b89a; }
    .inpatient-emr-field--readonly { padding: 1px 2px; background: #f1f4f5; }
  </style>
</head>
<body>${doc.body.innerHTML}</body>
</html>`
}
