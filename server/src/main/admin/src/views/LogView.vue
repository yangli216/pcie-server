<template>
  <div class="page-surface">
    <section class="page-section page-section--padded">
      <div class="page-toolbar__filters log-filter-grid">
        <el-input
          v-model.trim="filters.keyword"
          clearable
          placeholder="搜索模块、标题、动作、原始数据、设备或机构…"
          class="search-input"
          @keyup.enter.native="handleSearch"
        />
        <el-select
          v-model="filters.logType"
          clearable
          placeholder="日志类型"
          class="filter-select"
        >
          <el-option
            v-for="item in logTypeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-select
          v-model="filters.module"
          clearable
          filterable
          placeholder="模块名称"
          class="filter-select"
        >
          <el-option
            v-for="item in moduleOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-input
          v-model.trim="filters.action"
          clearable
          placeholder="动作编码"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-input
          v-model.trim="filters.title"
          clearable
          placeholder="业务标题"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-input
          v-model.trim="filters.sourceModule"
          clearable
          placeholder="来源模块"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-input
          v-model.trim="filters.scene"
          clearable
          placeholder="业务场景"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-input
          v-model.trim="filters.traceId"
          clearable
          placeholder="Trace ID"
          class="filter-input filter-input--wide"
          @keyup.enter.native="handleSearch"
        />
        <el-input
          v-model.trim="filters.consultationId"
          clearable
          placeholder="问诊ID"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-select
          v-model="filters.result"
          clearable
          placeholder="结果"
          class="filter-select"
        >
          <el-option
            v-for="item in resultOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-date-picker
          v-model="filters.dateRange"
          type="datetimerange"
          clearable
          unlink-panels
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="yyyy-MM-dd HH:mm:ss"
          class="filter-date"
        />
        <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading">
      <el-table-column label="日志类型" width="112">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="logTypeMeta(row.sdLogType).type">
            {{ logTypeMeta(row.sdLogType).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="模块" min-width="120" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="log-main-text">{{ primaryDisplay(row.displayModule, row.naModule) }}</div>
          <div v-if="showRawMeta(row.displayModule, row.naModule)" class="log-sub-text">{{ row.naModule }}</div>
        </template>
      </el-table-column>
      <el-table-column label="业务标题" min-width="180" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="log-main-text">{{ primaryDisplay(row.displayTitle, row.opTitle || row.desOp) }}</div>
          <div v-if="showRawMeta(row.displayTitle, row.opTitle || row.desOp)" class="log-sub-text">{{ row.opTitle || row.desOp }}</div>
        </template>
      </el-table-column>
      <el-table-column label="动作编码" min-width="180" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="log-main-text">{{ primaryDisplay(row.displayAction, row.opAction) }}</div>
          <code-tag :value="row.opAction" />
        </template>
      </el-table-column>
      <el-table-column label="来源/场景" min-width="180" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="log-main-text">{{ formatDisplaySourceScene(row) }}</div>
          <div v-if="showRawSourceScene(row)" class="log-sub-text">{{ formatSourceScene(row.sourceModule, row.sceneCode) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="结果" width="88">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="resultMeta(row.opResult).type">
            {{ resultMeta(row.opResult).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="原始数据" min-width="220" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <code-tag class="log-summary" :value="summarizeRawData(row)" />
        </template>
      </el-table-column>
      <el-table-column label="操作时间" width="168">
        <template slot-scope="{ row }">
          {{ formatDateTime(row.operationTime) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="96" fixed="right">
        <template slot-scope="{ row }">
          <table-action @click="openRawData(row)">详情</table-action>
        </template>
      </el-table-column>
    </el-table>

    <div class="page-footer">
      <AdminPagination
        :current.sync="current"
        :size.sync="size"
        :total="total"
        @change="loadData"
      />
    </div>
    </section>

    <el-dialog
      v-if="payloadDialogVisible"
      title="日志详情"
      :visible.sync="payloadDialogVisible"
      width="88%"
      top="4vh"
      custom-class="log-detail-dialog"
    >
      <div v-if="payloadRecord" class="detail-grid">
        <div class="detail-card">
          <div class="detail-card__label">日志类型</div>
          <div class="detail-card__value">{{ logTypeMeta(payloadRecord.sdLogType).label }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">模块</div>
           <div class="detail-card__value">{{ primaryDisplay(payloadRecord.displayModule, payloadRecord.naModule) }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">业务标题</div>
           <div class="detail-card__value">{{ primaryDisplay(payloadRecord.displayTitle, payloadRecord.opTitle || payloadRecord.desOp) }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">动作编码</div>
           <div class="detail-card__value">{{ primaryDisplay(payloadRecord.displayAction, payloadRecord.opAction) }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">来源模块</div>
           <div class="detail-card__value">{{ primaryDisplay(payloadRecord.displaySourceModule, payloadRecord.sourceModule) }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">业务场景</div>
           <div class="detail-card__value">{{ primaryDisplay(payloadRecord.displayScene, payloadRecord.sceneCode) }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">Trace ID</div>
          <div class="detail-card__value">{{ displayText(payloadRecord.traceId) }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">问诊ID</div>
          <div class="detail-card__value">{{ displayText(payloadRecord.consultationId) }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">结果</div>
          <div class="detail-card__value">{{ resultMeta(payloadRecord.opResult).label }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">操作时间</div>
          <div class="detail-card__value">{{ formatDateTime(payloadRecord.operationTime) }}</div>
        </div>
      </div>
      <div class="payload-section-list">
        <section
          v-for="section in payloadDetailSections"
          :key="section.key"
          :class="['payload-section', section.collapsed ? 'is-collapsed' : '']"
        >
          <div class="payload-section__head">
            <button
              type="button"
              class="payload-section__toggle"
              :aria-expanded="!section.collapsed"
              :aria-label="`${section.collapsed ? '展开' : '收起'}${section.title}`"
              @click="togglePayloadSection(section)"
            >
              <i :class="section.collapsed ? 'el-icon-arrow-right' : 'el-icon-arrow-down'" />
              <span class="payload-section__title">{{ section.title }}</span>
            </button>
            <span class="payload-section__status">{{ section.status }}</span>
          </div>
          <json-detail-viewer
            v-if="!section.collapsed"
            :value="section.value"
            :raw-value="section.rawValue"
            :empty-text="section.emptyText"
          />
        </section>
      </div>
      <span slot="footer">
        <el-button @click="payloadDialogVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import JsonDetailViewer from '../components/log/JsonDetailViewer.vue'
import { CodeTag, TableAction } from '../components/ui'

const MODULE_LABELS = {
  feedback: '反馈弹层',
  settings_feedback: '设置页反馈',
  llm: 'AI 对话代理',
    ai: 'AI 代理',
  aliyunSpeech: '语音识别代理',
  operation: '操作日志',
  metric: '指标日志',
  session: '会话日志',
  ai_proxy: 'AI 代理日志',
  speech_proxy: '语音代理日志',
  api_call: '接口调用',
  button_click: '按钮点击',
  form_submit: '表单提交',
  error: '异常日志',
  view_change: '视图切换',
  'view:chat': '聊天页',
  'view:settings': '设置页',
  'view:consultation': '智能问诊页',
  'view:risk-alert': '风险提示页',
  'view:voice-interaction': '语音胶囊',
  'view:voice-result': '语音结果页',
  'view:voice-consultation': '语音问诊页',
  'view:reception-capsule': '接待胶囊',
  'view:analytics': '数据分析页',
  'view:symptom-manage': '症状库维护页',
  'view:knowledge-base': '知识库页',
  chat: '聊天助手',
  consultation: '智能问诊',
  voice_consultation: '语音问诊',
  voice_capture: '语音采集',
    settings: '设置',
  reception: '接诊风险评估',
  reviewer: '独立审查AI',
  his_bridge: 'HIS 桥接',
  regional_runtime: '区域化运行时',
  diagnosis_path: '诊断路径',
  navigation: '页面导航',
    shell: '应用壳层',
    consultation_ai: '问诊 AI',
    consultation_reference: '问诊引用',
    consultation_record: '问诊病历',
    system_integration: '系统集成',
    lab_test: '检验项目',
    procedure: '处置建议',
    examination: '检查项目'
}

const MODULE_OPTIONS = [
  { value: 'feedback', label: MODULE_LABELS.feedback },
  { value: 'settings_feedback', label: MODULE_LABELS.settings_feedback },
  { value: 'llm', label: MODULE_LABELS.llm },
  { value: 'aliyunSpeech', label: MODULE_LABELS.aliyunSpeech },
  { value: 'operation', label: MODULE_LABELS.operation },
  { value: 'metric', label: MODULE_LABELS.metric },
  { value: 'session', label: MODULE_LABELS.session },
  { value: 'ai_proxy', label: MODULE_LABELS.ai_proxy },
  { value: 'speech_proxy', label: MODULE_LABELS.speech_proxy },
  { value: 'api_call', label: MODULE_LABELS.api_call },
  { value: 'button_click', label: MODULE_LABELS.button_click },
  { value: 'form_submit', label: MODULE_LABELS.form_submit },
  { value: 'error', label: MODULE_LABELS.error },
  { value: 'view_change', label: MODULE_LABELS.view_change },
  { value: 'view:chat', label: MODULE_LABELS['view:chat'] },
  { value: 'view:settings', label: MODULE_LABELS['view:settings'] },
  { value: 'view:consultation', label: MODULE_LABELS['view:consultation'] },
  { value: 'view:risk-alert', label: MODULE_LABELS['view:risk-alert'] },
  { value: 'view:voice-interaction', label: MODULE_LABELS['view:voice-interaction'] },
  { value: 'view:voice-result', label: MODULE_LABELS['view:voice-result'] },
  { value: 'view:voice-consultation', label: MODULE_LABELS['view:voice-consultation'] },
  { value: 'view:reception-capsule', label: MODULE_LABELS['view:reception-capsule'] },
  { value: 'view:analytics', label: MODULE_LABELS['view:analytics'] },
  { value: 'view:symptom-manage', label: MODULE_LABELS['view:symptom-manage'] },
  { value: 'view:knowledge-base', label: MODULE_LABELS['view:knowledge-base'] },
  { value: 'chat', label: MODULE_LABELS.chat },
  { value: 'consultation', label: MODULE_LABELS.consultation },
  { value: 'voice_consultation', label: MODULE_LABELS.voice_consultation },
  { value: 'voice_capture', label: MODULE_LABELS.voice_capture },
    { value: 'settings', label: MODULE_LABELS.settings },
  { value: 'reception', label: MODULE_LABELS.reception },
  { value: 'reviewer', label: MODULE_LABELS.reviewer },
  { value: 'his_bridge', label: MODULE_LABELS.his_bridge },
  { value: 'regional_runtime', label: MODULE_LABELS.regional_runtime },
  { value: 'diagnosis_path', label: MODULE_LABELS.diagnosis_path },
  { value: 'navigation', label: MODULE_LABELS.navigation },
    { value: 'shell', label: MODULE_LABELS.shell },
    { value: 'consultation_ai', label: MODULE_LABELS.consultation_ai },
    { value: 'consultation_reference', label: MODULE_LABELS.consultation_reference },
    { value: 'consultation_record', label: MODULE_LABELS.consultation_record },
    { value: 'system_integration', label: MODULE_LABELS.system_integration },
    { value: 'ai', label: MODULE_LABELS.ai },
    { value: 'procedure', label: MODULE_LABELS.procedure },
    { value: 'lab_test', label: MODULE_LABELS.lab_test },
    { value: 'examination', label: MODULE_LABELS.examination }
]

function createDefaultFilters() {
  return {
    keyword: '',
    logType: '',
    module: '',
    action: '',
    title: '',
    sourceModule: '',
    scene: '',
    traceId: '',
    consultationId: '',
    result: '',
    dateRange: []
  }
}

export default {
  components: {
    CodeTag,
    JsonDetailViewer,
    TableAction
  },
  data() {
    return {
      loading: false,
      current: 1,
      size: 10,
      total: 0,
      records: [],
      filters: createDefaultFilters(),
      payloadDialogVisible: false,
      payloadRecord: null,
      payloadDetailSections: [],
      moduleOptions: MODULE_OPTIONS,
      logTypeOptions: [
        { value: 'operation', label: '操作日志', type: 'info' },
        { value: 'feedback', label: '反馈日志', type: 'warning' },
        { value: 'metric', label: '指标日志', type: 'success' },
        { value: 'session', label: '会话日志', type: 'info' },
        { value: 'ai_proxy', label: 'AI 代理', type: 'danger' },
        { value: 'speech_proxy', label: '语音代理', type: 'warning' }
      ],
      resultOptions: [
        { value: 'success', label: '成功' },
        { value: 'failure', label: '失败' }
      ]
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      try {
        const dateRange = Array.isArray(this.filters.dateRange) ? this.filters.dateRange : []
        const data = await http.get('/admin/api/logs', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.filters.keyword || undefined,
            logType: this.filters.logType || undefined,
            module: this.filters.module || undefined,
            action: this.filters.action || undefined,
            title: this.filters.title || undefined,
            sourceModule: this.filters.sourceModule || undefined,
            scene: this.filters.scene || undefined,
            traceId: this.filters.traceId || undefined,
            consultationId: this.filters.consultationId || undefined,
            result: this.filters.result || undefined,
            dateFrom: dateRange[0] || undefined,
            dateTo: dateRange[1] || undefined
          }
        })
        this.records = data.records || []
        this.total = data.total || 0
      } catch (error) {
        this.$message.error(error.message || '加载失败')
      } finally {
        this.loading = false
      }
    },
    handleSearch() {
      this.current = 1
      this.loadData()
    },
    reset() {
      this.filters = createDefaultFilters()
      this.current = 1
      this.loadData()
    },
    openRawData(row) {
      this.payloadRecord = row
      this.payloadDetailSections = this.buildPayloadDetailSections(row)
      this.payloadDialogVisible = true
    },
    normalizeText(value) {
      if (value === null || value === undefined) {
        return ''
      }
      const text = String(value).trim()
      if (!text || text === 'null' || text === 'undefined') {
        return ''
      }
      return text
    },
    displayText(value) {
      return this.normalizeText(value) || '--'
    },
      primaryDisplay(displayValue, rawValue) {
        return this.displayText(this.normalizeText(displayValue) || rawValue)
      },
      showRawMeta(displayValue, rawValue) {
        const displayText = this.normalizeText(displayValue)
        const rawText = this.normalizeText(rawValue)
        return !!displayText && !!rawText && displayText !== rawText
      },
    moduleLabel(value) {
      const text = this.displayText(value)
      if (text === '--') {
        return text
      }
      return MODULE_LABELS[text] || text
    },
    formatDateTime(value) {
      const text = this.normalizeText(value)
      if (!text) {
        return '--'
      }
      return text.replace('T', ' ').replace(/\.\d+$/, '')
    },
    logTypeMeta(value) {
      const text = this.normalizeText(value)
      const matched = this.logTypeOptions.find(item => item.value === text)
      if (matched) {
        return matched
      }
      return {
        label: text || '--',
        type: 'info'
      }
    },
    resultMeta(value) {
      const text = this.normalizeText(value)
      const normalized = text.toLowerCase()
      if (normalized === '1' || normalized === 'true' || normalized === 'success' || normalized === 'ok') {
        return { label: '成功', type: 'success' }
      }
      if (normalized === '0' || normalized === 'false' || normalized === 'fail' || normalized === 'failure' || normalized === 'error') {
        return { label: '失败', type: 'danger' }
      }
      if (!text) {
        return { label: '--', type: 'info' }
      }
      return { label: text, type: 'info' }
    },
    summarizeRawData(row) {
      const text = this.normalizeText(row && row.payloadJson)
      if (!text) {
        return '无原始数据'
      }
      try {
        const payload = JSON.parse(text)
        const summaryParts = []
        const moduleName = this.primaryDisplay(row && row.displayModule, payload.module)
        const sourceScene = this.formatDisplaySourceScene(row)
        const title = this.primaryDisplay(row && row.displayTitle, payload.title)
        const action = this.primaryDisplay(row && row.displayAction, payload.action)
        const operationName = this.normalizeText(payload.operationName)
        const details = payload && typeof payload === 'object' && payload.details && typeof payload.details === 'object'
          ? payload.details
          : {}
        const requestPayload = this.pickFirstPayload(details.requestPayload, payload.requestPayload, payload.requestBody)
        const responsePayload = this.pickFirstPayload(details.responsePayload, payload.responsePayload, payload.responseBody)
        const responseText = this.normalizeText(payload.responseText || details.responseSummary || payload.responseSummary)
        const errorMessage = this.normalizeText(payload.errorMessage || details.errorMessage)
        if (moduleName) {
          summaryParts.push(moduleName)
        }
        if (title && title !== '--') {
          summaryParts.push(title)
        }
        if (action && action !== '--') {
          summaryParts.push(action)
        } else if (operationName) {
          summaryParts.push(operationName)
        }
        if (sourceScene !== '--') {
          summaryParts.push(sourceScene)
        }
        if (errorMessage) {
          summaryParts.push(`失败: ${this.truncate(errorMessage, 40)}`)
        } else if (this.hasPayloadContent(requestPayload) || this.hasPayloadContent(responsePayload)) {
          summaryParts.push('出入参已记录')
        } else if (responseText) {
          summaryParts.push(`回文: ${this.truncate(responseText, 40)}`)
        }
        if (summaryParts.length > 0) {
          return summaryParts.join(' / ')
        }
        return this.truncate(JSON.stringify(payload), 100)
      } catch (error) {
        return this.truncate(text.replace(/\s+/g, ' '), 100)
      }
    },
    buildPayloadDetailSections(row) {
      const payload = this.parsePayloadJson(row && row.payloadJson)
      const details = payload && typeof payload === 'object' && payload.details && typeof payload.details === 'object'
        ? payload.details
        : {}
      const requestPayload = this.pickFirstPayload(
        details.requestPayload,
        payload && payload.requestPayload,
        payload && payload.requestBody,
        details.requestBody
      )
      const responsePayload = this.pickFirstPayload(
        details.responsePayload,
        payload && payload.responsePayload,
        payload && payload.responseBody,
        payload && payload.responseText,
        payload && payload.upstreamBody,
        details.responseBody,
        details.responseText
      )
      const requestSummary = this.pickFirstPayload(details.requestSummary, payload && payload.requestSummary)
      const responseSummary = this.pickFirstPayload(details.responseSummary, payload && payload.responseSummary)
      const sections = [
        {
          key: 'request',
          title: '完整入参',
          value: requestPayload,
          fallback: requestSummary,
          emptyText: '未记录完整入参'
        },
        {
          key: 'response',
          title: '完整出参',
          value: responsePayload,
          fallback: responseSummary,
          emptyText: '未记录完整出参'
        },
        {
          key: 'payload',
          title: '原始 Payload',
          value: payload,
          fallback: row && row.payloadJson,
          emptyText: '无原始数据'
        }
      ]
      return sections.map((section) => {
        const hasFullValue = this.hasPayloadContent(section.value)
        const hasFallback = this.hasPayloadContent(section.fallback)
        const value = hasFullValue ? section.value : section.fallback
        return {
          key: section.key,
          title: section.title,
          status: hasFullValue ? '已完整记录' : hasFallback ? '仅有摘要/兼容字段' : section.emptyText,
          value,
          rawValue: section.key === 'payload' && row ? row.payloadJson : value,
          emptyText: section.emptyText,
          collapsed: false
        }
      })
    },
    parsePayloadJson(value) {
      const text = this.normalizeText(value)
      if (!text) {
        return null
      }
      try {
        return JSON.parse(text)
      } catch (error) {
        return text
      }
    },
    pickFirstPayload(...values) {
      for (const value of values) {
        if (this.hasPayloadContent(value)) {
          return value
        }
      }
      return undefined
    },
    hasPayloadContent(value) {
      if (value === null || value === undefined) {
        return false
      }
      if (typeof value === 'string') {
        return !!this.normalizeText(value)
      }
      return true
    },
    togglePayloadSection(section) {
      section.collapsed = !section.collapsed
    },
    truncate(value, maxLength) {
      if (!value || value.length <= maxLength) {
        return value
      }
      return `${value.slice(0, maxLength)}…`
    },
    formatSourceScene(sourceModule, sceneCode) {
      const left = this.normalizeText(sourceModule)
      const right = this.normalizeText(sceneCode)
      if (left && right) {
        return `${left} / ${right}`
      }
      return left || right || '--'
      },
      formatDisplaySourceScene(row) {
        return this.formatSourceScene(row && row.displaySourceModule, row && row.displayScene)
      },
      showRawSourceScene(row) {
        return this.formatDisplaySourceScene(row) !== this.formatSourceScene(row && row.sourceModule, row && row.sceneCode)
    }
  }
}
</script>

<style scoped>
.log-filter-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
}

.search-input {
  width: 260px;
}

.filter-select {
  width: 150px;
}

.filter-input {
  width: 160px;
}

.filter-input--wide {
  width: 220px;
}

.filter-date {
  width: 340px;
}

.log-summary {
  max-width: 100%;
  display: inline-block;
  overflow: hidden;
  text-overflow: ellipsis;
  vertical-align: middle;
  white-space: nowrap;
}

.log-main-text {
  color: #2C2C2A;
  line-height: 1.5;
}

.log-sub-text {
  margin-top: 2px;
  color: #888780;
  font-size: 12px;
  line-height: 1.4;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.detail-card {
  padding: 12px;
  border-radius: 8px;
  background: #F9FCFB;
  border: 0.5px solid #E8EEEC;
}

.detail-card__label {
  margin-bottom: 6px;
  font-size: 12px;
  color: #888780;
}

.detail-card__value {
  color: #2C2C2A;
  font-weight: 500;
  word-break: break-all;
}

.payload-section-list {
  display: grid;
  gap: 14px;
}

.payload-section {
  min-width: 0;
  padding-top: 14px;
  border-top: 1px solid var(--border-color-light);
}

.payload-section:first-child {
  padding-top: 0;
  border-top: 0;
}

.payload-section__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.payload-section.is-collapsed .payload-section__head {
  margin-bottom: 0;
}

.payload-section__toggle {
  min-width: 0;
  display: inline-flex;
  align-items: center;
  gap: 7px;
  padding: 3px 4px 3px 0;
  border: 0;
  border-radius: 4px;
  background: transparent;
  color: var(--color-text-primary);
  cursor: pointer;
}

.payload-section__toggle:hover {
  color: var(--color-primary-hover);
}

.payload-section__toggle:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 2px;
}

.payload-section__title {
  color: #2C2C2A;
  font-size: 14px;
  font-weight: 600;
}

.payload-section__status {
  color: #888780;
  font-size: 12px;
  white-space: nowrap;
}

:deep(.log-detail-dialog) {
  max-width: 1760px;
}

:deep(.log-detail-dialog .el-dialog__body) {
  max-height: 82vh;
  padding-top: 14px;
  overflow-y: auto;
}

@media (max-width: 960px) {
  .search-input,
  .filter-input,
  .filter-input--wide,
  .filter-select,
  .filter-date {
    width: 100%;
  }

  .payload-section__head {
    align-items: flex-start;
  }

  .payload-section__status {
    white-space: normal;
    text-align: right;
  }
}
</style>
