<template>
  <div class="page-surface">
    <section class="page-section page-section--padded">
    <div class="page-toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model.trim="filters.keyword"
          clearable
          placeholder="搜索反馈内容、医生、科室、机构…"
          class="search-input"
          @keyup.enter.native="handleSearch"
        />
        <el-select v-model="filters.kind" clearable placeholder="反馈类型" class="filter-select">
          <el-option v-for="item in KIND_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
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
          class="filter-date feedback-date-range"
        />
        <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <advanced-filter-panel v-model="advancedMode">
          <el-select
            v-model="filters.scores"
            multiple
            clearable
            collapse-tags
            placeholder="评分"
            class="filter-select"
          >
            <el-option v-for="value in scoreOptions" :key="value" :label="`${value} 分`" :value="value" />
          </el-select>
          <el-input v-model.trim="filters.doctor" clearable placeholder="医生" class="filter-narrow" />
          <el-input v-model.trim="filters.dept" clearable placeholder="科室" class="filter-narrow" />
          <el-input v-model.trim="filters.org" clearable placeholder="机构" class="filter-narrow" />
          <el-select v-model="filters.severity" clearable placeholder="严重度" class="filter-select">
            <el-option v-for="item in SEVERITY_OPTIONS" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-select v-model="filters.sourceModule" clearable filterable placeholder="来源模块" class="filter-select">
            <el-option
              v-for="item in sourceModuleOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
          <el-select v-model="filters.hasCorrection" clearable placeholder="是否含修正" class="filter-select">
            <el-option label="包含医生修正" :value="true" />
            <el-option label="无修正" :value="false" />
          </el-select>
          <el-select v-model="filters.hasTrace" clearable placeholder="是否含 trace" class="filter-select">
            <el-option label="有 traceId" :value="true" />
            <el-option label="无 traceId" :value="false" />
          </el-select>
          <el-checkbox v-model="filters.includeHistory">包含历史修订</el-checkbox>
        </advanced-filter-panel>
      </div>
    </div>
    </section>

    <section class="page-section page-section--table">
    <el-table :data="records" v-loading="loading">
      <el-table-column label="评分" width="80">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="scoreTagType(row.score)">{{ row.score || '--' }} 分</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="类型" width="100">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="kindTagType(row.kind)">{{ kindLabel(row.kind) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="反馈目标" min-width="180" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span v-if="row.targetSummary">{{ row.targetSummary }}</span>
          <span v-else class="text-muted">--</span>
        </template>
      </el-table-column>
      <el-table-column label="反馈说明" min-width="220" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ displayText(row.comment) }}</template>
      </el-table-column>
      <el-table-column label="版本" width="100">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="row.latest ? 'success' : 'info'">v{{ row.revisionNo || 1 }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="标签" min-width="160">
        <template slot-scope="{ row }">
          <el-tag
            v-for="tag in (row.tags || [])"
            :key="tag"
            size="mini"
            class="tag-chip"
            type="info"
          >{{ feedbackTagLabel(tag) }}</el-tag>
          <span v-if="!row.tags || !row.tags.length" class="text-muted">--</span>
        </template>
      </el-table-column>
      <el-table-column label="医生" min-width="110">
        <template slot-scope="{ row }">{{ displayText(row.naDoctor) }}</template>
      </el-table-column>
      <el-table-column label="科室" min-width="110">
        <template slot-scope="{ row }">{{ displayText(row.naDept) }}</template>
      </el-table-column>
      <el-table-column label="机构" min-width="120">
        <template slot-scope="{ row }">{{ displayText(row.naOrg || row.idOrg) }}</template>
      </el-table-column>
      <el-table-column v-if="advancedMode" label="来源模块" min-width="130" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <div class="meta-main-text">{{ primaryDisplay(row.displaySourceModule, row.sourceModule) }}</div>
          <div v-if="showRawMeta(row.displaySourceModule, row.sourceModule)" class="meta-sub-text">{{ row.sourceModule }}</div>
        </template>
      </el-table-column>
      <el-table-column v-if="advancedMode" label="trace" min-width="130" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <el-tag v-if="row.hasTrace" size="mini" type="success">有 trace</el-tag>
          <span v-else class="text-muted">无</span>
        </template>
      </el-table-column>
      <el-table-column label="时间" min-width="160">
        <template slot-scope="{ row }">{{ formatDateTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="132" fixed="right">
        <template slot-scope="{ row }">
          <div class="table-actions">
            <button type="button" class="table-action" @click="openDetail(row)">查看详情</button>
          </div>
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

    <el-dialog title="反馈详情" :visible.sync="detailDialogVisible" width="1100px">
      <el-tabs v-if="detailData" v-model="detailTab" type="border-card">
        <el-tab-pane label="反馈摘要" name="summary">
          <div class="detail-section">
            <div class="section-title">基础信息</div>
            <div class="detail-grid">
              <div class="detail-card">
                <div class="detail-card__label">评分</div>
                <div class="detail-card__value">
                  <el-tag size="mini" :type="scoreTagType(detailData.feedback.score)">{{ detailData.feedback.score }} 分</el-tag>
                </div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">类型</div>
                <div class="detail-card__value">
                  <el-tag size="mini" :type="kindTagType(detailData.feedback.kind)">{{ kindLabel(detailData.feedback.kind) }}</el-tag>
                </div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">严重度</div>
                <div class="detail-card__value">{{ severityLabel(detailData.feedback.severity) }}</div>
              </div>
              <div class="detail-card detail-card--wide">
                <div class="detail-card__label">反馈目标</div>
                <div class="detail-card__value">
                  <span v-if="detailData.feedback.targetSummary">{{ detailData.feedback.targetSummary }}</span>
                  <span v-else class="text-muted">--</span>
                </div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">医生</div>
                <div class="detail-card__value">{{ displayText(detailData.feedback.naDoctor) }}</div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">科室</div>
                <div class="detail-card__value">{{ displayText(detailData.feedback.naDept) }}</div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">机构</div>
                <div class="detail-card__value">{{ displayText(detailData.feedback.naOrg || detailData.feedback.idOrg) }}</div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">来源模块</div>
                <div class="detail-card__value">
                  <div class="meta-main-text">{{ primaryDisplay(detailData.feedback.displaySourceModule, detailData.feedback.sourceModule) }}</div>
                  <div v-if="showRawMeta(detailData.feedback.displaySourceModule, detailData.feedback.sourceModule)" class="meta-sub-text">{{ detailData.feedback.sourceModule }}</div>
                </div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">提交时间</div>
                <div class="detail-card__value">{{ formatDateTime(detailData.feedback.createdAt) }}</div>
              </div>
            </div>
            <div class="tag-row" v-if="detailData.feedback.tags && detailData.feedback.tags.length">
              <span class="tag-row__label">问题标签：</span>
              <el-tag v-for="tag in detailData.feedback.tags" :key="tag" size="small" type="warning" class="tag-chip">{{ feedbackTagLabel(tag) }}</el-tag>
            </div>
          </div>

          <div class="detail-section">
            <div class="section-title">医生原话</div>
            <div class="comment-block">{{ detailData.feedback.comment || '--' }}</div>
          </div>

          <div class="detail-section" v-if="recordFieldDetail">
            <div class="section-title">字段对比（{{ recordFieldDetail.fieldLabel || recordFieldDetail.fieldKey }}）</div>
            <div class="diff-grid">
              <div class="diff-card">
                <div class="diff-card__label">AI 原文</div>
                <div class="diff-card__value">{{ recordFieldDetail.originalValue || '--' }}</div>
              </div>
              <div class="diff-card">
                <div class="diff-card__label">当前内容</div>
                <div class="diff-card__value">{{ recordFieldDetail.currentValue || '--' }}</div>
              </div>
              <div class="diff-card" v-if="recordFieldDetail.correctedValue">
                <div class="diff-card__label">医生修正</div>
                <div class="diff-card__value">{{ recordFieldDetail.correctedValue }}</div>
              </div>
            </div>
          </div>

          <div class="detail-section" v-if="recommendationDetail">
            <div class="section-title">推荐项详情</div>
            <div class="detail-grid">
              <div class="detail-card">
                <div class="detail-card__label">类型</div>
                <div class="detail-card__value">{{ targetTypeLabel(recommendationDetail.targetType) }}</div>
              </div>
              <div class="detail-card detail-card--wide">
                <div class="detail-card__label">推荐项</div>
                <div class="detail-card__value">{{ recommendationDetail.recommendationTitle || '--' }}</div>
              </div>
              <div class="detail-card" v-if="recommendationDetail.action">
                <div class="detail-card__label">医生处置</div>
                <div class="detail-card__value">{{ actionLabel(recommendationDetail.action) }}</div>
              </div>
              <div class="detail-card detail-card--wide" v-if="recommendationDetail.correctedValue">
                <div class="detail-card__label">修正结果</div>
                <div class="detail-card__value">{{ recommendationDetail.correctedValue }}</div>
              </div>
            </div>
          </div>

          <div class="detail-section" v-if="detailData.feedback.screenshotDataUrl">
            <div class="section-title">截图</div>
            <img class="preview-image" :src="detailData.feedback.screenshotDataUrl" :alt="detailData.feedback.screenshotFileName || '反馈截图'" />
          </div>
        </el-tab-pane>

        <el-tab-pane label="技术诊断" name="technical">
          <div class="detail-section">
            <div class="section-title">追踪信息</div>
            <div class="detail-grid">
              <div class="detail-card">
                <div class="detail-card__label">traceId</div>
                <div class="detail-card__value mono">{{ displayText(detailData.feedback.traceId) }}</div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">sessionId</div>
                <div class="detail-card__value mono">{{ displayText(detailData.feedback.sessionId) }}</div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">设备 ID</div>
                <div class="detail-card__value mono">{{ displayText(detailData.feedback.idDevice) }}</div>
              </div>
              <div class="detail-card">
                <div class="detail-card__label">含修正 / 含 trace</div>
                <div class="detail-card__value">
                  <el-tag size="mini" :type="detailData.feedback.hasCorrection ? 'warning' : 'info'">{{ detailData.feedback.hasCorrection ? '含修正' : '无修正' }}</el-tag>
                  <el-tag size="mini" :type="detailData.feedback.hasTrace ? 'success' : 'info'" class="tag-chip">{{ detailData.feedback.hasTrace ? '有 trace' : '无 trace' }}</el-tag>
                </div>
              </div>
            </div>
          </div>

          <div class="detail-section">
            <div class="section-title">链路上下文快照</div>
            <pre class="payload-block">{{ formatPayload(detailData.feedback.chainContext) }}</pre>
          </div>

          <div class="detail-section">
            <div class="section-title">调用链路时间线</div>
            <div v-if="detailData.timeline && detailData.timeline.length" class="timeline-list">
              <div v-for="(item, index) in detailData.timeline" :key="`${item.time}-${index}`" class="timeline-item">
                <div class="timeline-item__meta">
                  <el-tag size="mini" :type="timelineType(item.type)">{{ timelineTypeLabel(item.type) }}</el-tag>
                  <span>{{ formatDateTime(item.time) }}</span>
                  <span :class="['timeline-result', item.result]">{{ normalizeResult(item.result) }}</span>
                </div>
                <div class="timeline-item__title">{{ timelineTitle(item) }}</div>
                <pre class="payload-block small">{{ formatPayload(item.payload) }}</pre>
              </div>
            </div>
            <div v-else class="empty-state">暂无可关联的调用链路记录</div>
          </div>
        </el-tab-pane>
      </el-tabs>
      <span slot="footer">
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { AdvancedFilterPanel } from '../components/ui'

const MODULE_LABELS = {
  feedback: '反馈弹层',
  settings_feedback: '设置页反馈',
  voice_session: '语音问诊整页',
  voice_recommendation: '语音推荐项',
  voice_record_field: '语音病例字段',
  llm: 'AI 对话代理',
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
  'view:knowledge-base': '知识库页'
}

const TIMELINE_TYPE_LABELS = {
  feedback: '反馈提交',
  operation: '操作日志',
  metric: '指标日志',
  session: '会话日志',
  ai_proxy: 'AI 代理调用',
  speech_proxy: '语音代理调用'
}

const KIND_OPTIONS = [
  { value: 'general', label: '通用反馈' },
  { value: 'recommendation', label: '推荐项反馈' },
  { value: 'record_field', label: '病例字段反馈' },
  { value: 'session', label: '整页评分反馈' }
]

const SEVERITY_OPTIONS = [
  { value: 'low', label: '轻度' },
  { value: 'medium', label: '一般' },
  { value: 'high', label: '严重' }
]

const FEEDBACK_TAG_LABELS = {
  recommendation_quality: '推荐质量',
  data_accuracy: '数据准确性',
  workflow: '操作流程',
  stability: '系统稳定性',
  ui: '界面体验',
  other: '其他',
  irrelevant: '推荐不贴合当前病情',
  unsafe: '存在安全或禁忌风险',
  missing_context: '忽略了关键病史或上下文',
  catalog_mismatch: '标准库匹配不准确',
  writeback_unusable: '不便直接回写到 HIS',
  diagnosis_quality: '诊断建议质量一般',
  treatment_quality: '治疗建议质量一般',
  missing_information: '结果缺失关键信息',
  too_much_noise: '输出噪音较多',
  workflow_friction: '操作流程不顺手',
  inaccurate_expression: '表述不够准确',
  timeline_conflict: '病程时序不清晰',
  history_confusion: '病史归类不准确',
  unsupported_inference: '存在不可靠推断'
}

const SOURCE_MODULE_OPTIONS = Object.entries(MODULE_LABELS).map(([value, label]) => ({ value, label }))

function createDefaultFilters() {
  return {
    keyword: '',
    kind: '',
    severity: '',
    scores: [],
    doctor: '',
    dept: '',
    org: '',
    sourceModule: '',
    hasCorrection: '',
    hasTrace: '',
    includeHistory: false,
    dateRange: []
  }
}

export default {
  components: {
    AdvancedFilterPanel
  },
  data() {
    return {
      loading: false,
      detailLoading: false,
      advancedMode: false,
      detailTab: 'summary',
      current: 1,
      size: 10,
      total: 0,
      records: [],
      filters: createDefaultFilters(),
      scoreOptions: [1, 2, 3, 4, 5],
      sourceModuleOptions: SOURCE_MODULE_OPTIONS,
      KIND_OPTIONS,
      SEVERITY_OPTIONS,
      detailDialogVisible: false,
      detailData: null
    }
  },
  mounted() {
    this.loadData()
  },
  computed: {
    recordFieldDetail() {
      const ctx = this.detailData && this.detailData.feedback && this.detailData.feedback.chainContext
      if (!ctx || typeof ctx !== 'object') return null
      const node = ctx.recordField
      if (!node || typeof node !== 'object') return null
      return node
    },
    recommendationDetail() {
      const ctx = this.detailData && this.detailData.feedback && this.detailData.feedback.chainContext
      if (!ctx || typeof ctx !== 'object') return null
      const node = ctx.recommendation
      if (!node || typeof node !== 'object') return null
      return node
    }
  },
  methods: {
    async loadData() {
      this.loading = true
      try {
        const dateRange = Array.isArray(this.filters.dateRange) ? this.filters.dateRange : []
        const params = {
          current: this.current,
          size: this.size,
          keyword: this.filters.keyword || undefined,
          kind: this.filters.kind || undefined,
          severity: this.filters.severity || undefined,
          scores: this.buildScoreParam(),
          doctor: this.filters.doctor || undefined,
          dept: this.filters.dept || undefined,
          org: this.filters.org || undefined,
          sourceModule: this.filters.sourceModule || undefined,
          includeHistory: this.filters.includeHistory || undefined,
          dateFrom: dateRange[0] || undefined,
          dateTo: dateRange[1] || undefined
        }
        if (this.filters.hasCorrection !== '' && this.filters.hasCorrection !== null && this.filters.hasCorrection !== undefined) {
          params.hasCorrection = this.filters.hasCorrection
        }
        if (this.filters.hasTrace !== '' && this.filters.hasTrace !== null && this.filters.hasTrace !== undefined) {
          params.hasTrace = this.filters.hasTrace
        }
        const data = await http.get('/admin/api/feedbacks', { params })
        this.records = data.records || []
        this.total = data.total || 0
      } catch (error) {
        this.$message.error(error.message || '加载反馈列表失败')
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
    buildScoreParam() {
      const scores = Array.isArray(this.filters.scores) ? this.filters.scores : []
      if (!scores.length) return undefined
      return scores.join(',')
    },
    async openDetail(row) {
      this.detailLoading = true
      this.detailDialogVisible = true
      this.detailData = null
      this.detailTab = 'summary'
      try {
        this.detailData = await http.get(`/admin/api/feedbacks/${row.feedbackId}`)
      } catch (error) {
        this.$message.error(error.message || '加载反馈详情失败')
        this.detailDialogVisible = false
      } finally {
        this.detailLoading = false
      }
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
      if (text === '--') return text
      return MODULE_LABELS[text] || text
    },
    kindLabel(value) {
      const found = KIND_OPTIONS.find(item => item.value === value)
      return found ? found.label : (value || '--')
    },
    kindTagType(value) {
      switch (value) {
        case 'recommendation': return 'success'
        case 'record_field': return 'warning'
        case 'session': return 'danger'
        default: return 'info'
      }
    },
    severityLabel(value) {
      const found = SEVERITY_OPTIONS.find(item => item.value === value)
      return found ? found.label : (value || '一般')
    },
    feedbackTagLabel(value) {
      const text = this.normalizeText(value)
      if (!text) return '--'
      return FEEDBACK_TAG_LABELS[text] || text
    },
    targetTypeLabel(value) {
      switch (value) {
        case 'diagnosis': return '推荐诊断'
        case 'medication':
        case 'medicine': return '推荐用药'
        case 'exam': return '推荐检查'
        case 'lab': return '推荐检验'
        case 'procedure':
        case 'treatment': return '推荐处置'
        case 'chiefComplaint': return '主诉'
        case 'historyOfPresentIllness': return '现病史'
        case 'pastHistory': return '既往史'
        default: return value || '--'
      }
    },
    actionLabel(value) {
      switch (value) {
        case 'useful': return '已采纳'
        case 'corrected': return '已修正'
        case 'dissatisfied': return '不满意'
        case 'ignored': return '已忽略'
        default: return value || '--'
      }
    },
    formatDateTime(value) {
      const text = this.displayText(value)
      if (text === '--') return text
      return text.replace('T', ' ').replace(/\.\d+$/, '')
    },
    scoreTagType(score) {
      if (Number(score) >= 4) return 'success'
      if (Number(score) === 3) return 'warning'
      return 'danger'
    },
    formatPayload(value) {
      if (!value) return '无数据'
      try { return JSON.stringify(value, null, 2) } catch (error) { return String(value) }
    },
    timelineType(type) {
      const normalized = String(type || '').toLowerCase()
      if (normalized === 'feedback') return 'warning'
      if (normalized.indexOf('speech') > -1) return 'info'
      if (normalized.indexOf('ai') > -1) return 'danger'
      return 'success'
    },
    timelineTypeLabel(type) {
      const text = this.displayText(type)
      if (text === '--') return text
      return TIMELINE_TYPE_LABELS[text] || this.moduleLabel(text)
    },
    timelineTitle(item) {
      if (!item) return '--'
      const payload = item.payload || {}
      const explicitTitle = this.displayText(item.title)
      const payloadTitle = this.displayText(payload.title || payload.operationName || payload.action)
      const sourceModule = this.primaryDisplay(item.displaySourceModule, payload.sourceModule || payload.module)
      const traceId = this.displayText(payload.traceId)
      const parts = []
      if (explicitTitle !== '--') {
        parts.push(explicitTitle)
      } else {
        const typeLabel = this.timelineTypeLabel(item.type)
        if (typeLabel !== '--') parts.push(typeLabel)
      }
      if (sourceModule !== '--') parts.push(sourceModule)
      if (payloadTitle !== '--' && payloadTitle !== explicitTitle) parts.push(payloadTitle)
      if (traceId !== '--') parts.push(`traceId: ${traceId}`)
      return parts.length ? parts.join(' / ') : '--'
    },
    normalizeResult(value) {
      const normalized = String(value || '').toLowerCase()
      if (normalized === 'success' || normalized === '1') return '成功'
      if (normalized === 'failure' || normalized === '0') return '失败'
      return value || '--'
    }
  }
}
</script>

<style scoped>
.page-toolbar__filters {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}
.page-toolbar__filters--advanced {
  margin-top: 8px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}
.meta-main-text { color: #303133; line-height: 1.5; }
.meta-sub-text { margin-top: 2px; font-size: 12px; line-height: 1.4; color: #909399; }
.search-input { width: 280px; }
.filter-select { width: 150px; }
.filter-narrow { width: 130px; }
.filter-date { width: 360px; }
.filter-toggle { margin-left: 8px; }

.text-muted { color: #909399; }
.tag-chip { margin-right: 4px; margin-bottom: 4px; }

.detail-section {
  padding: 16px;
  border: 1px solid #ebeef5;
  border-radius: 12px;
  background: #fff;
  margin-bottom: 16px;
}
.section-title {
  font-size: 15px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 12px;
}
.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px;
}
.detail-card {
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
}
.detail-card--wide {
  grid-column: 1 / -1;
}
.diff-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
}
.diff-card {
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
  border: 1px solid #ebeef5;
}
.diff-card__label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 6px;
}
.diff-card__value {
  font-size: 13px;
  color: #303133;
  white-space: pre-wrap;
  word-break: break-word;
}
.detail-card__label {
  font-size: 12px;
  color: #909399;
  margin-bottom: 4px;
}
.detail-card__value {
  font-size: 13px;
  color: #303133;
  word-break: break-all;
}
.detail-card__value.mono {
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 12px;
}
.tag-row {
  margin-top: 12px;
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 6px;
}
.tag-row__label {
  font-size: 12px;
  color: #909399;
}
.comment-block {
  padding: 12px;
  border-radius: 10px;
  background: #f8fafc;
  line-height: 1.7;
  color: #303133;
  white-space: pre-wrap;
}
.preview-image {
  display: block;
  width: 100%;
  max-height: 360px;
  object-fit: contain;
  border-radius: 10px;
  background: #f5f7fa;
}
.timeline-list { display: flex; flex-direction: column; gap: 12px; }
.timeline-item {
  padding: 14px;
  border: 1px solid #ebeef5;
  border-radius: 10px;
  background: #fafcff;
}
.timeline-item__meta {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 8px;
  font-size: 12px;
  color: #909399;
}
.timeline-item__title { font-size: 14px; color: #303133; margin-bottom: 8px; }
.timeline-result.success { color: #67c23a; }
.timeline-result.failure { color: #f56c6c; }
.payload-block {
  margin: 0;
  padding: 12px;
  border-radius: 10px;
  background: #0f172a;
  color: #e2e8f0;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
}
.payload-block.small { max-height: 220px; overflow: auto; }
.empty-state { padding: 16px; text-align: center; color: #909399; }
</style>
