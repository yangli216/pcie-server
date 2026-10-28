<template>
  <div class="page-surface emr-qc-page">
    <template v-if="!detailOpen">
      <el-alert
        v-if="sessionError"
        :title="sessionError"
        type="error"
        :closable="false"
        show-icon
      />
      <div v-else-if="session && !session.available" class="emr-qc-page__session-warning">
        <el-alert
          :title="session.unavailableReason || '当前无法查询门诊病历'"
          type="warning"
          :closable="false"
          show-icon
        />
        <el-button v-if="sessionExpired" size="small" @click="relogin">重新登录</el-button>
      </div>

      <admin-filter-bar>
        <div class="filter-row">
          <div class="filter-item">
            <div class="filter-label">就诊日期</div>
            <time-range-filter
              v-model="preset"
              :options="timeOptions"
              :date-from.sync="dateFrom"
              :date-to.sync="dateTo"
              @input="changePreset"
              @custom-change="changeCustom"
            />
          </div>
          <div class="filter-item">
            <label for="emr-qc-patient" class="filter-label">患者姓名</label>
            <el-input
              id="emr-qc-patient"
              v-model="patientName"
              size="small"
              maxlength="64"
              clearable
              placeholder="输入患者姓名…"
              @keyup.enter.native="search"
            />
          </div>
          <div class="filter-item">
            <label for="emr-qc-dept" class="filter-label">开立科室</label>
            <el-select id="emr-qc-dept" v-model="deptName" size="small" filterable clearable
              placeholder="全部科室（本机构）" aria-label="开立科室" @change="changeDepartment">
              <el-option v-for="name in departmentOptions" :key="name" :value="name" :label="name" />
            </el-select>
          </div>
          <div class="filter-item">
            <label for="emr-qc-doctor" class="filter-label">开立医生</label>
            <el-select
              id="emr-qc-doctor"
              v-model="doctorUserId"
              size="small"
              filterable
              clearable
              placeholder="全部医生（本机构）"
              aria-label="开立医生"
            >
              <el-option
                v-for="item in filteredDoctors"
                :key="item.userId"
                :value="item.userId"
                :label="doctorOptionLabel(item)"
              />
            </el-select>
          </div>
          <div class="filter-item">
            <div class="filter-label">质控状态</div>
            <el-select v-model="qcStatus" size="small" aria-label="质控状态">
              <el-option v-for="item in qcStatuses" :key="item.value" :value="item.value" :label="item.label" />
            </el-select>
          </div>
          <div class="filter-actions">
            <el-button type="primary" size="small" :disabled="sessionBlocked" @click="search">查询</el-button>
            <el-button size="small" @click="reset">重置</el-button>
          </div>
        </div>
      </admin-filter-bar>

      <section v-if="task" class="page-section page-section--padded emr-qc-page__task">
        <div class="page-section__header">
          <div>
            <h2 class="page-section__title">批量质控进度</h2>
            <div class="emr-qc-page__hint">
              {{ taskStatusLabel(task.status) }} · 已处理 {{ task.processed || 0 }} / {{ task.total || 0 }}
              · 成功 {{ task.succeeded || 0 }} · 失败 {{ task.failed || 0 }}
            </div>
          </div>
          <div class="emr-qc-page__task-actions">
            <el-button size="small" icon="el-icon-refresh" :loading="taskLoading" @click="refreshTask">刷新</el-button>
            <el-button size="small" icon="el-icon-close" @click="dismissTask">收起</el-button>
          </div>
        </div>
        <el-progress
          :percentage="taskPercentage"
          :status="taskProgressStatus"
          :stroke-width="8"
          :show-text="false"
        />
        <el-table :data="task.items || []" size="small" empty-text="暂无任务明细" aria-label="批量质控进度明细">
          <el-table-column label="患者" min-width="110">
            <template slot-scope="{ row }">{{ row.patientName || '--' }}</template>
          </el-table-column>
          <el-table-column label="结果" width="110">
            <template slot-scope="{ row }">
              <status-pill :tone="taskItemTone(row.status)" :label="taskItemLabel(row.status)" />
            </template>
          </el-table-column>
          <el-table-column label="总分" width="80">
            <template slot-scope="{ row }">{{ row.score === null || row.score === undefined ? '--' : row.score }}</template>
          </el-table-column>
          <el-table-column label="问题数" width="80">
            <template slot-scope="{ row }">{{ row.findingCount === null || row.findingCount === undefined ? '--' : row.findingCount }}</template>
          </el-table-column>
          <el-table-column label="说明" min-width="220" show-overflow-tooltip>
            <template slot-scope="{ row }">
              <span :class="{ 'emr-qc-page__error': row.status === 'failed' }">
                {{ row.status === 'failed' ? (row.errorMessage || row.errorCode || '质控失败') : '--' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
      </section>

      <section class="page-section page-section--table emr-qc-page__list">
        <div class="page-section__header">
          <div>
            <h2 class="page-section__title">门诊病历质控</h2>
          </div>
          <div class="page-section__meta">
            <span>AI 质控结果仅作书写参考，不代表质控通过或医学审核通过</span>
            <el-button
              size="small"
              icon="el-icon-magic-stick"
              :loading="taskCreating"
              :disabled="sessionBlocked || !selectedRows.length"
              @click="createBatchTask"
            >
              批量质控{{ selectedRows.length ? `（${selectedRows.length}）` : '' }}
            </el-button>
          </div>
        </div>
        <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
        <div class="emr-qc-page__table-body">
          <el-table
            ref="recordTable"
            height="100%"
            v-loading="loading"
            :data="records"
            empty-text="当前条件下没有门诊病历"
            aria-label="门诊病历质控列表"
            @selection-change="handleSelectionChange"
          >
            <el-table-column type="selection" width="48" />
            <el-table-column label="患者" min-width="140" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <span class="emr-qc-page__patient-line">{{ [row.patientName, row.gender, row.age ? `${row.age} 岁` : ''].filter(Boolean).join(' / ') || '未记录' }}</span>
              </template>
            </el-table-column>
            <el-table-column label="科室 / 医生" min-width="140" show-overflow-tooltip>
              <template slot-scope="{ row }">{{ [row.deptName, row.doctorName].filter(Boolean).join(' / ') || '未记录' }}</template>
            </el-table-column>
            <el-table-column prop="templateName" label="文书模板" min-width="150" show-overflow-tooltip />
            <el-table-column label="就诊时间" width="128" class-name="emr-qc-page__time-cell">
              <template slot-scope="{ row }"><span :title="formatDateTime(row.visitTime)">{{ formatTimeShort(row.visitTime) }}</span></template>
            </el-table-column>
            <el-table-column label="更新时间" width="128" class-name="emr-qc-page__time-cell">
              <template slot-scope="{ row }"><span :title="formatDateTime(row.updateTime)">{{ formatTimeShort(row.updateTime) }}</span></template>
            </el-table-column>
            <el-table-column label="质控状态" width="84">
              <template slot-scope="{ row }">
                <status-pill :tone="qcTone(row.qcStatus)" :label="qcLabel(row.qcStatus)" />
              </template>
            </el-table-column>
            <el-table-column label="总分" width="56" align="center">
              <template slot-scope="{ row }">
                <span v-if="row.score === null || row.score === undefined">--</span>
                <span v-else :title="row.resultSource === 'manual' ? '结果已经人工调整' : null">{{ row.score }}<sup v-if="row.resultSource === 'manual'" class="emr-qc-page__manual-mark">人</sup></span>
              </template>
            </el-table-column>
            <el-table-column label="问题" width="104" class-name="emr-qc-page__finding-cell">
              <template slot-scope="{ row }">
                <template v-if="row.findingCount !== null && row.findingCount !== undefined">
                  {{ row.findingCount }}
                  <span v-if="row.highSeverityCount" class="emr-qc-page__error">· 重点 {{ row.highSeverityCount }}</span>
                </template>
                <template v-else>--</template>
              </template>
            </el-table-column>
            <el-table-column label="操作" width="116" fixed="right" class-name="emr-qc-page__actions-cell">
              <template slot-scope="{ row }">
                <span class="emr-qc-page__row-actions">
                  <el-button type="text" size="small" @click="openDetail(row.idMedrecdoc)">查看</el-button>
                  <el-button type="text" size="small" @click="analyzeRow(row)">质控</el-button>
                </span>
              </template>
            </el-table-column>
          </el-table>
        </div>
        <div class="page-footer">
          <admin-pagination :current.sync="current" :size.sync="size" :total="total" @change="loadData" />
        </div>
      </section>
    </template>

    <section v-else v-loading="detailLoading" class="emr-qc-page__detail" aria-label="门诊病历详情与质控结果">
      <div class="emr-qc-page__bar">
        <el-button size="small" icon="el-icon-arrow-left" @click="closeDetail">返回列表</el-button>
        <h1>门诊病历详情</h1>
        <div class="emr-qc-page__bar-actions">
          <el-button
            size="small"
            icon="el-icon-edit-outline"
            :disabled="!detail || !detail.qc || detail.qc.stale || detail.record.stale || detail.qc.ruleVersion !== 'emr-qc-rules-v4' || sessionBlocked || analyzing || reviewEditing || reviewing"
            @click="reviewEditing = true"
          >{{ reviewEditing ? '调整中' : '人工调整' }}</el-button>
          <el-button
            size="small"
            type="primary"
            icon="el-icon-magic-stick"
            :loading="analyzing"
            :disabled="!detail || sessionBlocked || reviewEditing || reviewing"
            @click="analyzeDetail(true)"
          >
            {{ detail && detail.qc ? '重新质控' : '立即质控' }}
          </el-button>
        </div>
      </div>

      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
      <template v-if="detail">
        <div v-if="detail.record.stale || (detail.qc && detail.qc.stale)" class="emr-qc-page__stale" role="alert">
          病历已更新，质控结果可能过期，建议重新质控。
        </div>
        <div v-if="detail.qc && detail.qc.ruleVersion !== 'emr-qc-rules-v4'" class="emr-qc-page__stale">评分规则已更新，请重新质控后进行人工调整。</div>
        <div ref="recordMeta" class="page-section page-section--padded emr-qc-page__meta" :class="{ 'emr-qc-page__meta--linked': activeTarget && activeTarget.scope === 'meta' }">
          <div><span>患者</span><strong>{{ [detail.record.patientName, detail.record.gender, detail.record.age ? `${detail.record.age} 岁` : ''].filter(Boolean).join(' / ') || '未记录' }}</strong></div>
          <div><span>身份证</span><strong>{{ detail.record.idCardMasked || '未记录' }}</strong></div>
          <div><span>科室 / 医生</span><strong>{{ [detail.record.deptName, detail.record.doctorName].filter(Boolean).join(' / ') || '未记录' }}</strong></div>
          <div><span>文书模板</span><strong>{{ detail.record.templateName || '未记录' }}</strong></div>
          <div><span>就诊时间</span><strong>{{ formatDateTime(detail.record.visitTime) }}</strong></div>
          <div><span>更新时间</span><strong>{{ formatDateTime(detail.record.updateTime) }}</strong></div>
        </div>

        <div class="emr-qc-page__columns">
          <chart-panel title="病历正文" class="emr-qc-page__panel" aria-label="病历正文面板">
            <div v-if="detail.htmlPreview" class="emr-qc-page__preview-toggle">
              <span>{{ showSections ? '问题联动视图' : 'PHIS 原始排版' }}</span>
              <button type="button" @click="togglePreview">{{ showSections ? '查看 PHIS 原始排版' : '切换问题联动视图' }}</button>
            </div>
            <iframe v-if="detail.htmlPreview && !showSections" :key="htmlPreviewKey" class="emr-qc-page__html-preview" :srcdoc="linkedHtmlPreview" sandbox="" referrerpolicy="no-referrer" title="病历 HTML 只读预览" />
            <div v-if="!detail.htmlPreview || showSections" ref="recordBody" class="emr-qc-page__panel-body" tabindex="0" aria-label="病历正文内容">
              <p v-if="!detail.sections.length" class="emr-qc-page__hint">未解析到可展示的病历章节</p>
              <section
                v-for="(section, index) in detail.sections"
                :key="`${section.name}-${index}`"
                :data-section-index="index"
                class="emr-qc-page__section"
                :class="{ 'emr-qc-page__section--linked': activeTarget && activeTarget.scope === 'section' && activeTarget.sectionIndex === index }"
                tabindex="-1"
              >
                <h3>{{ section.name }}：</h3>
                <div class="emr-qc-page__section-content">
                  <span
                    v-for="(field, fieldIndex) in section.fields"
                    :key="`${field.name}-${fieldIndex}`"
                    :data-field-index="fieldIndex"
                    class="emr-qc-page__field"
                    :class="{ 'emr-qc-page__field--linked': activeTarget && activeTarget.scope === 'section' && activeTarget.sectionIndex === index && activeTarget.fieldIndex === fieldIndex }"
                  >
                    <b v-if="showFieldName(section, field)">{{ field.name }}：</b>{{ field.value || '（空）' }}<template v-if="fieldIndex < section.fields.length - 1">；</template>
                  </span>
                </div>
              </section>
            </div>
          </chart-panel>

          <chart-panel title="AI 书写质控参考" class="emr-qc-page__panel" aria-label="质控参考面板">
            <emr-qc-result-panel
              :qc="detail.qc"
              :editing.sync="reviewEditing"
              :saving="reviewing"
              :active-finding-key="activeFindingKey"
              @save="saveReview"
              @locate="locateFinding"
            />
          </chart-panel>
        </div>
      </template>
    </section>
  </div>
</template>

<script>
import {
  analyzeRecord,
  createTask,
  fetchDetail,
  fetchDoctors,
  fetchLatestTask,
  fetchRecords,
  fetchSession,
  fetchTask,
  reviewRecord
} from '../api/emrQc'
import { AdminFilterBar, AdminPagination, ChartPanel, StatusPill, TimeRangeFilter } from '../components/ui'
import { assistantLogDateRange } from '../utils/assistantLogDates'
import { formatDateTime } from '../utils/admin'
import { clearAdminAuth } from '../utils/auth'
import { isWithinOneCalendarYear } from '../utils/emrQcDateRange'
import { decorateEmrHtmlPreview, departmentOptions, doctorsForDepartment, findingUsesStructuredPreview, normalizeEmrText, resolveFindingTarget } from '../utils/emrQcResults'
import EmrQcResultPanel from '../components/emr-qc/EmrQcResultPanel.vue'

const MAX_BATCH = 50

function formatTimeShort(value) {
  if (!value) return '--'
  const text = formatDateTime(value)
  const year = String(value).slice(0, 4)
  return year === String(new Date().getFullYear()) ? text.slice(5) : text
}

export default {
  name: 'EmrQcView',
  beforeRouteLeave(to, from, next) {
    if (!this.reviewEditing) { next(); return }
    this.$confirm('当前人工调整尚未保存，确定离开？', '未保存的调整', { type: 'warning' })
      .then(() => next()).catch(() => next(false))
  },
  components: { AdminFilterBar, AdminPagination, ChartPanel, StatusPill, TimeRangeFilter, EmrQcResultPanel },
  data() {
    return {
      preset: 'week',
      dateFrom: '',
      dateTo: '',
      patientName: '',
      deptName: '',
      doctorUserId: '',
      doctorOptions: [],
      qcStatus: 'all',
      timeOptions: [
        { value: 'day', label: '本日' },
        { value: 'week', label: '本周' },
        { value: 'month', label: '本月' },
        { value: 'custom', label: '自定义' }
      ],
      qcStatuses: [
        { value: 'all', label: '全部' },
        { value: 'pending', label: '待质控' },
        { value: 'analyzed', label: '已质控' },
        { value: 'stale', label: '结果过期' }
      ],
      session: null,
      sessionError: '',
      current: 1,
      size: 10,
      total: 0,
      records: [],
      applied: {},
      loading: false,
      listError: '',
      listSequence: 0,
      selectedRows: [],
      task: null,
      taskLoading: false,
      taskCreating: false,
      taskSequence: 0,
      pollTimer: null,
      detailOpen: false,
      detail: null,
      detailLoading: false,
      detailError: '',
      detailSequence: 0,
      analyzing: false,
      reviewEditing: false,
      showSections: false,
      activeFindingKey: null,
      activeTarget: null,
      reviewing: false
    }
  },
  computed: {
    departmentOptions() { return departmentOptions(this.doctorOptions) },
    filteredDoctors() { return doctorsForDepartment(this.doctorOptions, this.deptName) },
    sessionBlocked() {
      return Boolean(this.sessionError) || !this.session || !this.session.available
    },
    sessionExpired() {
      return Boolean(this.session && this.session.unavailableCode === 'EMR-PHIS-SESSION')
    },
    linkedHtmlPreview() {
      const preview = this.detail && this.detail.htmlPreview ? this.detail.htmlPreview : ''
      if (!this.activeTarget || !['missing', 'section'].includes(this.activeTarget.scope)) return preview
      return decorateEmrHtmlPreview(preview, this.activeTarget.label)
    },
    htmlPreviewKey() {
      return `${this.detail && this.detail.record ? this.detail.record.idMedrecdoc : ''}:${this.activeTarget ? `${this.activeTarget.scope}:${this.activeTarget.label}` : 'none'}`
    },
    taskPercentage() {
      if (!this.task || !this.task.total) {
        return 0
      }
      return Math.min(100, Math.round(((this.task.processed || 0) * 100) / this.task.total))
    },
    taskProgressStatus() {
      if (!this.task) {
        return undefined
      }
      if (this.task.status === 'failed') {
        return 'exception'
      }
      if (this.task.status === 'partially_completed') {
        return 'warning'
      }
      return this.task.status === 'completed' ? 'success' : undefined
    }
  },
  mounted() {
    this.loadSession()
    this.search()
    this.handleWindowResize = () => {
      if (this.$refs.recordTable) {
        this.$refs.recordTable.doLayout()
      }
    }
    window.addEventListener('resize', this.handleWindowResize)
  },
  beforeDestroy() {
    window.removeEventListener('resize', this.handleWindowResize)
    this.listSequence++
    this.detailSequence++
    this.taskSequence++
    if (this.pollTimer) {
      clearTimeout(this.pollTimer)
      this.pollTimer = null
    }
  },
  methods: {
    relogin() {
      clearAdminAuth()
      this.$router.replace({ path: '/login', query: { redirect: '/emr-qc' } })
    },
    locateFinding(payload) {
      if (!this.detail || !payload || !payload.finding) return
      this.activeFindingKey = payload.key
      this.activeTarget = resolveFindingTarget(this.detail.sections, payload.finding)
      this.showSections = findingUsesStructuredPreview(this.detail.htmlPreview)
      this.$nextTick(() => this.scrollToTarget())
    },
    scrollToTarget() {
      if (!this.activeTarget) return
      if (this.activeTarget.scope === 'meta') {
        const meta = this.$refs.recordMeta
        if (meta && meta.scrollIntoView) meta.scrollIntoView({ behavior: 'smooth', block: 'nearest' })
        return
      }
      const body = this.$refs.recordBody
      if (!body || this.activeTarget.scope !== 'section') return
      let target = null
      if (this.activeTarget.scope === 'section') {
        const section = body.querySelector(`[data-section-index="${this.activeTarget.sectionIndex}"]`)
        target = this.activeTarget.fieldIndex >= 0 && section
          ? section.querySelector(`[data-field-index="${this.activeTarget.fieldIndex}"]`)
          : section
      }
      if (!target) return
      body.scrollTop += target.getBoundingClientRect().top - body.getBoundingClientRect().top - 42
    },
    togglePreview() {
      this.showSections = !this.showSections
      if (this.showSections && this.activeTarget) this.$nextTick(() => this.scrollToTarget())
    },
    showFieldName(section, field) {
      const fields = Array.isArray(section.fields) ? section.fields : []
      if (fields.length <= 1) return false
      const sectionName = normalizeEmrText(section.name)
      const fieldName = normalizeEmrText(field.name).replace(/(?:文本|内容|标志)$/, '')
      return Boolean(fieldName) && fieldName !== sectionName && !fieldName.includes(sectionName) && !sectionName.includes(fieldName)
    },
    formatDateTime,
    formatTimeShort,
    async loadSession() {
      this.sessionError = ''
      try {
        this.session = await fetchSession()
        if (this.session && this.session.available) {
          this.loadDoctors()
          this.loadLatestTask()
        }
      } catch (error) {
        this.session = null
        this.sessionError = error.message
      }
    },
    async loadDoctors() {
      try {
        this.doctorOptions = await fetchDoctors() || []
      } catch (error) {
        this.doctorOptions = []
        this.$message.error(`科室及医生目录加载失败：${error.message}`)
      }
    },
    doctorOptionLabel(item) {
      return !this.deptName && item.deptName ? `${item.name}（${item.deptName}）` : item.name
    },
    changeDepartment() {
      if (this.doctorUserId && !this.filteredDoctors.some(item => item.userId === this.doctorUserId)) {
        this.doctorUserId = ''
      }
    },
    changePreset() {
      if (this.preset !== 'custom') {
        this.search()
      }
    },
    changeCustom() {
      if (this.dateFrom && this.dateTo) {
        this.search()
      }
    },
    resolveRange() {
      if (this.preset === 'custom') {
        if (!this.dateFrom || !this.dateTo) {
          this.$message.warning('请同时选择开始和结束日期')
          return null
        }
        if (this.dateFrom > this.dateTo) {
          this.$message.warning('开始日期不能晚于结束日期')
          return null
        }
        if (!isWithinOneCalendarYear(this.dateFrom, this.dateTo)) {
          this.$message.warning('查询日期跨度不能超过 1 年')
          return null
        }
        return { dateFrom: this.dateFrom, dateTo: this.dateTo }
      }
      return assistantLogDateRange(this.preset)
    },
    search() {
      if (this.sessionBlocked) {
        return
      }
      const range = this.resolveRange()
      if (!range) {
        return
      }
      this.applied = {
        ...range,
        patientName: this.patientName.trim() || undefined,
        deptName: this.deptName || undefined,
        doctorUserId: this.doctorUserId || undefined,
        qcStatus: this.qcStatus
      }
      this.current = 1
      this.loadData()
    },
    reset() {
      this.preset = 'week'
      this.dateFrom = ''
      this.dateTo = ''
      this.patientName = ''
      this.deptName = ''
      this.doctorUserId = ''
      this.qcStatus = 'all'
      this.search()
    },
    async loadData() {
      const sequence = ++this.listSequence
      this.loading = true
      this.listError = ''
      this.records = []
      this.selectedRows = []
      try {
        const data = await fetchRecords({ ...this.applied, current: this.current, size: this.size })
        if (sequence !== this.listSequence) {
          return
        }
        this.records = data.records || []
        this.total = data.total || 0
        this.current = data.current || this.current
        this.size = data.size || this.size
      } catch (error) {
        if (sequence === this.listSequence) {
          this.listError = error.message
          this.total = 0
        }
      } finally {
        if (sequence === this.listSequence) {
          this.loading = false
        }
      }
    },
    handleSelectionChange(rows) {
      this.selectedRows = rows || []
    },
    async analyzeRow(row) {
      try {
        this.loading = true
        const analysis = await analyzeRecord(row.idMedrecdoc, false)
        this.$message.success(
          analysis.reused ? '已复用未过期的质控结果，可在详情中查看' : `质控完成，总分 ${analysis.score}`
        )
        await this.loadData()
        if (this.detailOpen) {
          await this.loadDetail(this.detail.record.idMedrecdoc)
        }
      } catch (error) {
        this.$message.error(error.message)
      } finally {
        this.loading = false
      }
    },
    async openDetail(idMedrecdoc) {
      this.detailOpen = true
      await this.loadDetail(idMedrecdoc)
    },
    async loadDetail(idMedrecdoc) {
      const sequence = ++this.detailSequence
      this.reviewEditing = false
      this.detail = null
      this.showSections = false
      this.activeFindingKey = null
      this.activeTarget = null
      this.detailError = ''
      this.detailLoading = true
      try {
        const detail = await fetchDetail(idMedrecdoc)
        if (sequence === this.detailSequence) {
          this.detail = detail
          this.showSections = !detail.htmlPreview
        }
      } catch (error) {
        if (sequence === this.detailSequence) {
          this.detailError = error.message
        }
      } finally {
        if (sequence === this.detailSequence) {
          this.detailLoading = false
        }
      }
    },
    async closeDetail() {
      if (this.reviewEditing) {
        try { await this.$confirm('当前人工调整尚未保存，确定返回列表？', '未保存的调整', { type: 'warning' }) } catch (_) { return }
      }
      this.detailSequence++
      this.reviewEditing = false
      this.detailOpen = false
      this.detail = null
      this.activeFindingKey = null
      this.activeTarget = null
      this.loadData()
    },
    async analyzeDetail(force) {
      if (force && this.detail && this.detail.qc && this.detail.qc.resultSource === 'manual') {
        try { await this.$confirm('重新质控后，新 AI 结果将成为当前结果。原人工调整记录仍会保留。', '重新质控', { type: 'warning' }) } catch (_) { return }
      }
      if (!this.detail) {
        return
      }
      this.analyzing = true
      try {
        const analysis = await analyzeRecord(this.detail.record.idMedrecdoc, force)
        this.detail = { ...this.detail, qc: analysis, record: { ...this.detail.record, stale: Boolean(analysis.stale) } }
        this.activeFindingKey = null
        this.activeTarget = null
        this.$message.success(
          analysis.reused ? '已复用未过期的质控结果' : `质控完成，总分 ${analysis.score}`
        )
      } catch (error) {
        this.$message.error(error.message)
      } finally {
        this.analyzing = false
      }
    },
    async saveReview(payload) {
      if (!this.detail || !this.detail.qc) {
        return
      }
      this.reviewing = true
      try {
        const analysis = await reviewRecord(this.detail.record.idMedrecdoc, payload)
        this.detail = { ...this.detail, qc: analysis }
        this.activeFindingKey = null
        this.activeTarget = null
        this.records = this.records.map(row => row.idMedrecdoc === this.detail.record.idMedrecdoc
          ? {
              ...row,
              score: analysis.score,
              findingCount: analysis.findingCount,
              highSeverityCount: analysis.highSeverityCount,
              resultSource: analysis.resultSource
            }
          : row)
        this.reviewEditing = false
        this.$message.success(`人工调整已保存，总分 ${analysis.score}`)
      } catch (error) {
        this.$message.error(error.message)
      } finally {
        this.reviewing = false
      }
    },
    async createBatchTask() {
      if (!this.selectedRows.length) {
        return
      }
      if (this.selectedRows.length > MAX_BATCH) {
        this.$message.warning(`单批最多 ${MAX_BATCH} 份病历`)
        return
      }
      this.taskCreating = true
      try {
        const created = await createTask(this.selectedRows.map(row => row.idMedrecdoc), false)
        this.$message.success(`已创建批量质控任务，共 ${created.total} 份`)
        this.selectedRows = []
        if (this.$refs.recordTable) {
          this.$refs.recordTable.clearSelection()
        }
        await this.pollTask(created.taskId)
      } catch (error) {
        this.$message.error(error.message)
      } finally {
        this.taskCreating = false
      }
    },
    async loadLatestTask() {
      try {
        const task = await fetchLatestTask()
        if (task) {
          this.task = task
          if (task.status === 'queued' || task.status === 'running') {
            this.scheduleTaskPoll(task.taskId, 2000)
          }
        }
      } catch (error) {
        // 最近任务只是辅助恢复进度，读取失败不阻塞列表。
      }
    },
    async pollTask(taskId) {
      const sequence = ++this.taskSequence
      this.taskLoading = true
      try {
        const task = await fetchTask(taskId)
        if (sequence !== this.taskSequence) {
          return
        }
        this.task = task
        if (task.status === 'queued' || task.status === 'running') {
          this.scheduleTaskPoll(taskId, 2500)
        } else {
          this.loadData()
        }
      } catch (error) {
        if (sequence === this.taskSequence) {
          this.$message.error(error.message)
        }
      } finally {
        if (sequence === this.taskSequence) {
          this.taskLoading = false
        }
      }
    },
    scheduleTaskPoll(taskId, delay) {
      if (this.pollTimer) {
        clearTimeout(this.pollTimer)
      }
      this.pollTimer = setTimeout(() => {
        this.pollTimer = null
        this.pollTask(taskId)
      }, delay)
    },
    refreshTask() {
      if (this.task && this.task.taskId) {
        this.pollTask(this.task.taskId)
      }
    },
    dismissTask() {
      if (this.pollTimer) {
        clearTimeout(this.pollTimer)
        this.pollTimer = null
      }
      this.taskSequence++
      this.task = null
    },
    qcTone(status) {
      if (status === 'analyzed') {
        return 'success'
      }
      if (status === 'stale') {
        return 'warning'
      }
      return 'muted'
    },
    qcLabel(status) {
      return { pending: '待质控', analyzed: '已质控', stale: '结果过期' }[status] || '待质控'
    },
    taskStatusLabel(status) {
      return {
        queued: '排队中',
        running: '质控中',
        completed: '已完成',
        partially_completed: '部分失败',
        failed: '失败'
      }[status] || status
    },
    taskItemTone(status) {
      if (status === 'succeeded') {
        return 'success'
      }
      if (status === 'failed') {
        return 'danger'
      }
      return 'muted'
    },
    taskItemLabel(status) {
      return { pending: '待处理', succeeded: '成功', failed: '失败' }[status] || status
    },
    severityTone(severity) {
      if (severity === 'high') {
        return 'danger'
      }
      if (severity === 'medium') {
        return 'warning'
      }
      return 'muted'
    },
    severityLabel(severity) {
      return { high: '高', medium: '中', low: '低', info: '提示' }[severity] || severity || '提示'
    },
    clampPercent(value) {
      const number = Number(value)
      if (!Number.isFinite(number)) {
        return 0
      }
      return Math.max(0, Math.min(100, Math.round(number)))
    }
  }
}
</script>

<style scoped>
.emr-qc-page {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.emr-qc-page > div,
.emr-qc-page > section {
  min-width: 0;
  flex-shrink: 0;
}
.emr-qc-page .admin-filter-bar {
  max-height: 35%;
  overflow: auto;
}
.emr-qc-page__task {
  max-height: 28%;
  overflow: auto;
}
.emr-qc-page > .emr-qc-page__list {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.emr-qc-page__list .page-section__header,
.emr-qc-page__list .page-footer {
  flex-shrink: 0;
}
.emr-qc-page__list .page-section__meta {
  flex-wrap: wrap;
  justify-content: flex-end;
}
.emr-qc-page__table-body {
  flex: 1;
  min-width: 0;
  min-height: 0;
  overflow: hidden;
}
.emr-qc-page ::v-deep .el-table {
  min-width: 0;
  width: 100%;
  max-width: 100%;
}
.emr-qc-page > .emr-qc-page__detail {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  min-height: 0;
  overflow: hidden;
}
.emr-qc-page__detail > :not(.emr-qc-page__columns) {
  flex-shrink: 0;
}
.emr-qc-page h1 {
  margin: 0;
  font-size: 17px;
  font-weight: 600;
}
.emr-qc-page__hint {
  margin-top: 4px;
  font-size: 12px;
  color: var(--color-text-secondary);
}
.emr-qc-page__patient-line {
  display: inline-block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  vertical-align: middle;
}
.emr-qc-page__row-actions {
  display: inline-flex;
  align-items: center;
  white-space: nowrap;
}
.emr-qc-page__row-actions .el-button {
  height: 28px;
  padding: 0 2px;
}
.emr-qc-page__row-actions .el-button + .el-button {
  margin-left: 8px;
}
.emr-qc-page ::v-deep .emr-qc-page__time-cell .cell {
  font-size: 12px;
}
.emr-qc-page ::v-deep .emr-qc-page__actions-cell .cell,
.emr-qc-page ::v-deep .emr-qc-page__time-cell .cell,
.emr-qc-page ::v-deep .emr-qc-page__finding-cell .cell {
  white-space: nowrap;
}
.emr-qc-page ::v-deep .emr-qc-page__finding-cell .cell {
  overflow: hidden;
  text-overflow: ellipsis;
}
.emr-qc-page__error {
  color: #c0392b;
}
.emr-qc-page__manual-mark {
  margin-left: 2px;
  color: #b57b1e;
  font-size: 10px;
}
.emr-qc-page__meta span {
  font-size: 12px;
  color: var(--color-text-secondary);
}
.emr-qc-page__meta strong {
  font-size: 13px;
  overflow-wrap: anywhere;
}
.emr-qc-page__task-actions {
  display: flex;
  gap: 8px;
}
.emr-qc-page .el-progress {
  margin: 12px 0;
}
.emr-qc-page__bar {
  display: flex;
  align-items: center;
  gap: 16px;
}
.emr-qc-page__bar-actions {
  margin-left: auto;
}
.emr-qc-page__stale {
  padding: 10px 14px;
  border: 1px solid #f0c36d;
  border-radius: 8px;
  background: #fffbe6;
  color: #8a6d3b;
  font-size: 13px;
}
.emr-qc-page__meta {
  display: grid;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 12px 18px;
  max-height: 112px;
  overflow: auto;
}
.emr-qc-page__meta--linked {
  border-color: var(--color-primary);
  box-shadow: 0 0 0 2px rgba(47, 157, 120, 0.13);
}
.emr-qc-page__meta > div {
  display: grid;
  gap: 4px;
}
.emr-qc-page__columns {
  display: grid;
  flex: 1;
  min-height: 0;
  grid-template-columns: minmax(0, 43fr) minmax(0, 57fr);
  grid-template-rows: minmax(0, 1fr);
  gap: 12px;
  overflow: hidden;
}
.emr-qc-page__panel {
  display: flex;
  flex-direction: column;
  min-width: 0;
  min-height: 0;
  padding: 13px 15px;
  overflow: hidden;
}
.emr-qc-page__panel ::v-deep .chart-panel__header {
  flex-shrink: 0;
}
.emr-qc-page__panel-body {
  flex: 1;
  min-height: 0;
  padding: 2px 6px 18px 0;
  overflow: auto;
  overscroll-behavior: contain;
  scrollbar-gutter: stable;
  overflow-wrap: anywhere;
}
.emr-qc-page__html-preview {
  display: block;
  flex: 1;
  width: 100%;
  min-height: 0;
  border: 0;
  background: #fff;
}
.emr-qc-page__preview-toggle { display: flex; align-items: center; justify-content: flex-end; gap: 8px; min-height: 24px; text-align: right; flex-shrink: 0; }
.emr-qc-page__preview-toggle span { padding: 2px 7px; border-radius: 10px; background: #eef7f3; color: #357760; font-size: 11px; }
.emr-qc-page__preview-toggle button { padding: 2px 0; border: 0; background: transparent; color: var(--color-primary); cursor: pointer; font-size: 12px; }
.emr-qc-page__preview-toggle button:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.emr-qc-page__section {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  padding: 4px 9px;
  border-left: 3px solid transparent;
  border-radius: 4px;
  font-size: 13px;
  line-height: 1.72;
}
.emr-qc-page__section h3 {
  flex: 0 0 auto;
  margin: 0;
  padding: 0;
  font-size: 13px;
  font-weight: 700;
  color: var(--color-text-primary);
}
.emr-qc-page__section-content { min-width: 0; flex: 1; white-space: pre-wrap; overflow-wrap: anywhere; }
.emr-qc-page__section--linked { border-left-color: var(--color-primary); background: #f2faf7; }
.emr-qc-page__field {
  display: inline;
  border-radius: 3px;
  color: var(--color-text-regular);
}
.emr-qc-page__field b { color: var(--color-text-primary); font-weight: 600; }
.emr-qc-page__field--linked { padding: 2px 4px; background: #dff3ea; color: #214f3f; box-shadow: 0 0 0 1px #90c8b1; }
.emr-qc-page .el-alert {
  margin-bottom: 12px;
}
.emr-qc-page__session-warning {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
}
.emr-qc-page__session-warning .el-alert {
  flex: 1;
  margin-bottom: 0;
}
.emr-qc-page__session-warning .el-button {
  flex-shrink: 0;
}
@media (max-width: 1100px) {
  .emr-qc-page__meta {
    grid-template-columns: repeat(3, minmax(0, 1fr));
    padding: 12px 16px;
  }
  .emr-qc-page__columns {
    grid-template-columns: minmax(0, 46fr) minmax(0, 54fr);
  }
}
@media (max-width: 760px) {
  .emr-qc-page__columns {
    grid-template-columns: minmax(0, 1fr);
    grid-template-rows: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }
  .emr-qc-page__detail {
    gap: 10px;
  }
  .emr-qc-page__meta {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    max-height: 88px;
  }
  .emr-qc-page__panel {
    padding: 10px 12px;
  }
  .emr-qc-page__section { gap: 5px; padding-right: 5px; padding-left: 6px; }
}
</style>
