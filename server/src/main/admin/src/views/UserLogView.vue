<template>
  <div class="page-surface">
    <section class="page-section page-section--padded">
    <div class="page-toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model.trim="filters.keyword"
          clearable
          placeholder="搜索机构、医生、患者、业务 ID…"
          class="search-input"
          @keyup.enter.native="handleSearch"
        />
        <el-select v-model="filters.consultationType" clearable placeholder="场景类型" class="filter-select">
          <el-option
            v-for="item in consultationTypeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-select v-model="filters.status" clearable placeholder="处理结果" class="filter-select">
          <el-option
            v-for="item in statusOptions"
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
          :picker-options="datePickerOptions"
          class="filter-date"
        />
        <el-select v-model="filters.changesRange" clearable placeholder="修改次数" class="filter-select">
          <el-option
            v-for="item in changesRangeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
        <el-button @click="reset">重置</el-button>
        <el-button icon="el-icon-download" :loading="exporting" @click="handleExport">导出</el-button>
      </div>
    </div>
    </section>

    <section class="page-section page-section--table">
    <el-table :data="records" v-loading="loading">
      <el-table-column label="机构" min-width="150" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ displayText(row.naOrg || row.idOrg) }}</template>
      </el-table-column>
<!--      <el-table-column label="HIS机构ID" min-width="130" show-overflow-tooltip>-->
<!--        <template slot-scope="{ row }">{{ displayText(row.hisOrgId) }}</template>-->
<!--      </el-table-column>-->
      <el-table-column label="医生" min-width="120" show-overflow-tooltip>
        <template slot-scope="{ row }">{{ displayText(row.naDoctor || row.idDoctor) }}</template>
      </el-table-column>
      <el-table-column label="业务时间" width="168">
        <template slot-scope="{ row }">{{ formatDateTime(row.consultationTime) }}</template>
      </el-table-column>
      <el-table-column label="患者" min-width="150" show-overflow-tooltip>
        <template slot-scope="{ row }">
          <span>{{ displayText(row.patientName || row.patientId) }}</span>
          <span v-if="row.patientGender || row.patientAge" class="patient-meta">
            {{ [row.patientGender, row.patientAge].filter(Boolean).join(' / ') }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="场景类型" width="120">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="consultationTypeMeta(row.consultationType).type">
            {{ consultationTypeMeta(row.consultationType).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="语音输入" width="130">
        <template slot-scope="{ row }">
          <el-tag v-if="row.hasAudio" size="mini" type="success">录音</el-tag>
          <el-tag v-if="row.hasSpeechText" size="mini" type="info" class="inline-tag">识别</el-tag>
          <span v-if="!row.hasAudio && !row.hasSpeechText" class="empty-inline">--</span>
        </template>
      </el-table-column>
      <el-table-column label="处理结果" width="110">
        <template slot-scope="{ row }">
          <el-tag size="mini" :type="statusMeta(row.status, row.consultationType).type">
            {{ statusMeta(row.status, row.consultationType).label }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="修改数" width="80" align="center">
        <template slot-scope="{ row }">
          <span v-if="row.totalChanges != null && row.totalChanges > 0">{{ row.totalChanges }}</span>
          <span v-else class="empty-inline">--</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="96" fixed="right">
        <template slot-scope="{ row }">
          <el-button type="text" size="mini" @click="openDetail(row)">详情</el-button>
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

    <el-dialog title="用户日志详情" :visible.sync="detailDialogVisible" width="1120px" @closed="handleDetailClosed">
      <div v-if="detailRecord" v-loading="detailLoading">
        <div class="detail-grid">
          <div class="detail-card">
            <div class="detail-card__label">机构</div>
            <div class="detail-card__value">{{ displayText(detailRecord.naOrg || detailRecord.idOrg) }}</div>
          </div>
          <div class="detail-card">
            <div class="detail-card__label">HIS机构ID</div>
            <div class="detail-card__value mono">{{ displayText(detailRecord.hisOrgId) }}</div>
          </div>
          <div class="detail-card">
            <div class="detail-card__label">医生</div>
            <div class="detail-card__value">{{ displayText(detailRecord.naDoctor || detailRecord.idDoctor) }}</div>
          </div>
          <div class="detail-card">
            <div class="detail-card__label">业务时间</div>
            <div class="detail-card__value">{{ formatDateTime(detailRecord.consultationTime) }}</div>
          </div>
          <div class="detail-card">
            <div class="detail-card__label">场景类型</div>
            <div class="detail-card__value">{{ consultationTypeMeta(detailRecord.consultationType).label }}</div>
          </div>
          <div class="detail-card">
            <div class="detail-card__label">患者</div>
            <div class="detail-card__value">
              {{ displayText(detailRecord.patientName || detailRecord.patientId) }}
              <span v-if="detailRecord.patientGender || detailRecord.patientAge" class="patient-meta">
                {{ [detailRecord.patientGender, detailRecord.patientAge].filter(Boolean).join(' / ') }}
              </span>
            </div>
          </div>
          <div class="detail-card">
            <div class="detail-card__label">业务ID</div>
            <div class="detail-card__value mono">{{ displayText(detailRecord.consultationId) }}</div>
          </div>
        </div>

        <div v-if="shouldShowSpeechSection(detailRecord)" class="speech-section">
          <div class="snapshot-panel__title">语音与文字识别</div>
          <div class="speech-grid">
            <div class="speech-card">
              <div class="record-field__label">录音播放</div>
              <div v-if="detailRecord.hasAudio">
                <div class="audio-meta">
                  {{ displayText(detailRecord.audioFileName) }}
                  <span v-if="detailRecord.audioSize"> / {{ formatBytes(detailRecord.audioSize) }}</span>
                </div>
                <div v-if="audioLoading" class="empty-inline">录音加载中…</div>
                <audio v-else-if="audioObjectUrl" class="audio-player" :src="audioObjectUrl" controls preload="metadata" />
                <div v-else class="audio-error">{{ audioLoadError || '录音暂不可播放' }}</div>
              </div>
              <div v-else class="empty-inline">--</div>
            </div>
            <div class="speech-card speech-card--text">
              <div class="record-field__label">文字识别</div>
              <div class="speech-text">{{ displayText(detailRecord.speechText) }}</div>
            </div>
          </div>
        </div>

        <div v-if="!isReportInterpretationDetail" class="snapshot-compare">
          <div class="snapshot-panel">
            <div class="snapshot-panel__title">首次生成内容</div>
            <div v-for="field in recordFieldDefinitions" :key="field.key" class="record-field">
              <div class="record-field__label">{{ field.label }}</div>
              <div :class="['record-field__value', { multiline: field.multiline }]">
                {{ snapshotText(firstSnapshot, field.key) }}
              </div>
            </div>
            <SnapshotList title="诊断" :items="snapshotItems(firstSnapshot, 'diagnoses')" />
            <SnapshotList title="用药" :items="snapshotItems(firstSnapshot, 'medicines')" />
            <SnapshotList title="检查" :items="snapshotItems(firstSnapshot, 'examinations')" />
            <SnapshotList title="检验" :items="snapshotItems(firstSnapshot, 'labTests')" />
            <SnapshotList title="处置" :items="snapshotItems(firstSnapshot, 'procedures')" />
          </div>

          <div class="snapshot-panel">
            <div class="snapshot-panel__title">最终修改内容</div>
            <div v-for="field in recordFieldDefinitions" :key="field.key" class="record-field">
              <div class="record-field__label">{{ field.label }}</div>
              <div :class="['record-field__value', { multiline: field.multiline }]">
                <DiffValue
                  :old-value="snapshotRaw(firstSnapshot, field.key)"
                  :new-value="snapshotRaw(finalSnapshot, field.key)"
                />
              </div>
            </div>
            <SnapshotList
              title="诊断"
              :items="snapshotItems(finalSnapshot, 'diagnoses')"
              :previous-items="snapshotItems(firstSnapshot, 'diagnoses')"
              diff-mode
            />
            <SnapshotList
              title="用药"
              :items="snapshotItems(finalSnapshot, 'medicines')"
              :previous-items="snapshotItems(firstSnapshot, 'medicines')"
              diff-mode
            />
            <SnapshotList
              title="检查"
              :items="snapshotItems(finalSnapshot, 'examinations')"
              :previous-items="snapshotItems(firstSnapshot, 'examinations')"
              diff-mode
            />
            <SnapshotList
              title="检验"
              :items="snapshotItems(finalSnapshot, 'labTests')"
              :previous-items="snapshotItems(firstSnapshot, 'labTests')"
              diff-mode
            />
            <SnapshotList
              title="处置"
              :items="snapshotItems(finalSnapshot, 'procedures')"
              :previous-items="snapshotItems(firstSnapshot, 'procedures')"
              diff-mode
            />
          </div>
        </div>

        <div v-else class="snapshot-panel report-interpretation-detail">
          <div class="snapshot-panel__title">报告解读内容</div>
          <div class="detail-grid">
            <div class="detail-card">
              <div class="detail-card__label">报告类型</div>
              <div class="detail-card__value">{{ displayText(reportScenarioSnapshot.reportKindLabel) }}</div>
            </div>
            <div class="detail-card">
              <div class="detail-card__label">报告项目</div>
              <div class="detail-card__value">{{ displayText(reportScenarioSnapshot.reportTitle || reportScenarioSnapshot.reportItem) }}</div>
            </div>
            <div class="detail-card">
              <div class="detail-card__label">报告日期</div>
              <div class="detail-card__value">{{ displayText(reportScenarioSnapshot.reportDate) }}</div>
            </div>
          </div>
          <div class="record-field">
            <div class="record-field__label">报告原文</div>
            <div class="record-field__value multiline">{{ displayText(reportScenarioSnapshot.sourceText) }}</div>
          </div>
          <div class="record-field">
            <div class="record-field__label">解读摘要</div>
            <div class="record-field__value multiline">{{ displayText(reportScenarioSnapshot.summary) }}</div>
          </div>
          <div class="record-field">
            <div class="record-field__label">综合结论</div>
            <div class="record-field__value multiline">{{ displayText(reportScenarioSnapshot.conclusion) }}</div>
          </div>
          <SnapshotList title="异常项目" :items="reportAbnormalItems" />
          <SnapshotList title="解读要点" :items="reportKeyPoints" />
          <SnapshotList title="处理建议" :items="reportRecommendations" />
        </div>

        <div v-if="!isReportInterpretationDetail" class="selection-section">
          <div class="snapshot-panel__title">最终选中状态</div>
          <div class="selection-grid">
            <div class="selection-card">
              <div class="record-field__label">诊断</div>
              <div class="selection-card__value">{{ selectedNames('selectedDiagnosisNames') }}</div>
            </div>
            <div class="selection-card">
              <div class="record-field__label">用药</div>
              <div class="selection-card__value">{{ selectedNames('selectedMedicineNames') }}</div>
            </div>
            <div class="selection-card">
              <div class="record-field__label">检查</div>
              <div class="selection-card__value">{{ selectedNames('selectedExaminationNames') }}</div>
            </div>
            <div class="selection-card">
              <div class="record-field__label">检验</div>
              <div class="selection-card__value">{{ selectedNames('selectedLabTestNames') }}</div>
            </div>
            <div class="selection-card">
              <div class="record-field__label">处置</div>
              <div class="selection-card__value">{{ selectedNames('selectedProcedureNames') }}</div>
            </div>
          </div>
        </div>

        <div v-if="timelineItems.length" class="timeline-section">
          <div class="snapshot-panel__title">业务流程</div>
          <el-timeline>
            <el-timeline-item
              v-for="(item, index) in timelineItems"
              :key="index"
              :timestamp="formatDateTime(item.operationTime)"
              placement="top"
              :type="item.result === '1' || item.result === 'success' ? 'success' : item.result === '0' ? 'danger' : 'primary'"
            >
              <div class="timeline-content">
                <div class="timeline-primary-row">
                  <span class="timeline-module">{{ primaryDisplay(item.displayModule, item.module || item.eventType) }}</span>
                  <span class="timeline-action">{{ primaryDisplay(item.displayAction, item.action) }}</span>
                </div>
                <div v-if="showTimelineRaw(item)" class="timeline-raw-row">
                  <span>{{ item.module || item.eventType }}</span>
                  <span>{{ item.action }}</span>
                </div>
              </div>
            </el-timeline-item>
          </el-timeline>
        </div>
      </div>
      <span slot="footer">
        <el-button @click="detailDialogVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'

function normalizeTextValue(value) {
  if (value === null || value === undefined) return ''
  const text = String(value).trim()
  if (!text || text === 'null' || text === 'undefined') return ''
  return text
}

const RECORD_FIELD_DEFINITIONS = Object.freeze([
  { key: 'chiefComplaint', label: '主诉', multiline: false },
  { key: 'historyOfPresentIllness', label: '现病史', multiline: true },
  { key: 'pastMedicalHistory', label: '既往史', multiline: true },
  { key: 'personalHistory', label: '个人史', multiline: true },
  { key: 'familyHistory', label: '家族史', multiline: true },
  { key: 'physicalExam', label: '体格检查', multiline: true },
  { key: 'precautions', label: '注意事项', multiline: true }
])

const DiffValue = {
  props: {
    oldValue: {
      type: [String, Number],
      default: ''
    },
    newValue: {
      type: [String, Number],
      default: ''
    }
  },
  render(h) {
    const oldText = normalizeTextValue(this.oldValue)
    const newText = normalizeTextValue(this.newValue)

    if (!oldText && !newText) {
      return h('span', { class: 'empty-inline' }, '--')
    }
    if (oldText === newText) {
      return h('span', newText || '--')
    }

    const children = []
    if (oldText) {
      children.push(h('span', { class: 'diff-removed' }, oldText))
    }
    if (newText) {
      children.push(h('span', { class: 'diff-added' }, newText))
    } else {
      children.push(h('span', { class: 'empty-inline' }, '--'))
    }
    return h('span', { class: 'diff-value' }, children)
  }
}

const SnapshotList = {
  props: {
    title: {
      type: String,
      required: true
    },
    items: {
      type: Array,
      default: () => []
    },
    previousItems: {
      type: Array,
      default: () => []
    },
    diffMode: {
      type: Boolean,
      default: false
    }
  },
  methods: {
    displayItem(item) {
      if (!item) return '--'
      if (typeof item === 'string') return item
      return item.name || item.naSrv || item.naDiag || item.title || item.code || '--'
    },
    itemMeta(item) {
      if (!item || typeof item === 'string') return ''
      return [
        item.code,
        item.spec,
        item.dosage,
        item.frequency,
        item.route,
        item.execDept
      ].filter(Boolean).join(' / ')
    },
    itemSignature(item) {
      return [this.displayItem(item), this.itemMeta(item)].filter(Boolean).join(' / ')
    },
    buildDiffEntries() {
      if (!this.diffMode) {
        return (this.items || []).map(item => ({ type: 'same', item }))
      }

      const previous = this.previousItems || []
      const current = this.items || []
      const usedCurrentIndexes = new Set()
      const entries = []

      previous.forEach(previousItem => {
        const previousSignature = this.itemSignature(previousItem)
        const matchedIndex = current.findIndex((currentItem, index) => {
          return !usedCurrentIndexes.has(index) && this.itemSignature(currentItem) === previousSignature
        })
        if (matchedIndex >= 0) {
          usedCurrentIndexes.add(matchedIndex)
          entries.push({ type: 'same', item: current[matchedIndex] })
        } else {
          entries.push({ type: 'removed', item: previousItem })
        }
      })

      current.forEach((currentItem, index) => {
        if (!usedCurrentIndexes.has(index)) {
          entries.push({ type: 'added', item: currentItem })
        }
      })

      return entries
    }
  },
  render(h) {
    const entries = this.buildDiffEntries()
    const content = entries.length
      ? h('div', { class: 'snapshot-list__items' }, entries.map((entry, index) => {
        const item = entry.item
        const meta = this.itemMeta(item)
        const children = [
          h('span', { class: 'snapshot-list__name' }, this.displayItem(item))
        ]
        if (item && typeof item === 'object' && item.selected) {
          children.push(h('el-tag', { props: { size: 'mini', type: 'success' } }, '已选'))
        }
        if (meta) {
          children.push(h('span', { class: 'snapshot-list__meta' }, meta))
        }
        return h('div', { key: index, class: ['snapshot-list__item', `snapshot-list__item--${entry.type}`] }, children)
      }))
      : h('div', { class: 'empty-inline' }, '--')
    return h('div', { class: 'snapshot-list' }, [
      h('div', { class: 'record-field__label' }, this.title),
      content
    ])
  }
}

function createDefaultFilters() {
  return {
    keyword: '',
    consultationType: '',
    status: '',
    dateRange: [],
    changesRange: ''
  }
}

function getDateShortcuts() {
  const now = new Date()
  const today = new Date(now.getFullYear(), now.getMonth(), now.getDate())
  return [
    { text: '今日', onClick(picker) { picker.$emit('pick', [today, now]) } },
    { text: '本周', onClick(picker) {
      const day = today.getDay() || 7
      const start = new Date(today)
      start.setDate(start.getDate() - day + 1)
      picker.$emit('pick', [start, now])
    }},
    { text: '本月', onClick(picker) {
      const start = new Date(now.getFullYear(), now.getMonth(), 1)
      picker.$emit('pick', [start, now])
    }},
    { text: '本季度', onClick(picker) {
      const quarter = Math.floor(now.getMonth() / 3)
      const start = new Date(now.getFullYear(), quarter * 3, 1)
      picker.$emit('pick', [start, now])
    }},
    { text: '本年', onClick(picker) {
      const start = new Date(now.getFullYear(), 0, 1)
      picker.$emit('pick', [start, now])
    }}
  ]
}

export default {
  components: {
    DiffValue,
    SnapshotList
  },
  data() {
    return {
      loading: false,
      detailLoading: false,
      exporting: false,
      current: 1,
      size: 10,
      total: 0,
      records: [],
      filters: createDefaultFilters(),
      detailDialogVisible: false,
      detailRecord: null,
      timelineItems: [],
      audioObjectUrl: '',
      audioLoading: false,
      audioLoadError: '',
      recordFieldDefinitions: RECORD_FIELD_DEFINITIONS,
      consultationTypeOptions: [
        { value: 'voice', label: '语音问诊', type: 'success' },
        { value: 'smart', label: '智能问诊', type: 'primary' },
        { value: 'chronic_refill', label: '慢病配药', type: 'warning' },
        { value: 'report_consultation', label: '报告回诊', type: 'primary' },
        { value: 'report_interpretation', label: '报告解读', type: 'info' }
      ],
      statusOptions: [
        { value: 'generated', label: '已生成' },
        { value: 'completed', label: '已完成/回写' },
        { value: 'abandoned', label: '放弃' }
      ],
      datePickerOptions: {
        shortcuts: getDateShortcuts()
      },
      changesRangeOptions: [
        { value: '1-3', label: '1～3次' },
        { value: '3-5', label: '3～5次' },
        { value: '5+', label: '5次以上' }
      ]
    }
  },
  computed: {
    firstSnapshot() {
      return this.parsePayload(this.detailRecord && this.detailRecord.firstSnapshotJson)
    },
    finalSnapshot() {
      return this.parsePayload(this.detailRecord && this.detailRecord.finalSnapshotJson)
    },
    selectionSnapshot() {
      return this.parsePayload(this.detailRecord && this.detailRecord.selectionJson)
    },
    isReportInterpretationDetail() {
      return this.normalizeText(this.detailRecord && this.detailRecord.consultationType) === 'report_interpretation'
    },
    reportScenarioSnapshot() {
      return (this.finalSnapshot && this.finalSnapshot.scenario) ||
        (this.firstSnapshot && this.firstSnapshot.scenario) || {}
    },
    reportAbnormalItems() {
      const items = this.reportScenarioSnapshot.abnormalItems
      if (!Array.isArray(items)) return []
      return items.map(item => ({
        name: item && item.name,
        code: [item && item.result, item && item.direction, item && item.referenceRange].filter(Boolean).join(' / ')
      }))
    },
    reportKeyPoints() {
      const items = this.reportScenarioSnapshot.keyPoints
      if (!Array.isArray(items)) return []
      return items.map(item => ({ name: item && item.title, code: item && item.detail }))
    },
    reportRecommendations() {
      const recommendations = this.reportScenarioSnapshot.recommendations
      const cautions = this.reportScenarioSnapshot.cautions
      return [
        ...(Array.isArray(recommendations) ? recommendations : []),
        ...(Array.isArray(cautions) ? cautions.map(item => `注意：${item}`) : [])
      ]
    }
  },
  mounted() {
    this.loadData()
  },
  beforeDestroy() {
    this.clearAudioObjectUrl()
  },
  methods: {
    async loadData() {
      this.loading = true
      try {
        const dateRange = Array.isArray(this.filters.dateRange) ? this.filters.dateRange : []
        const changes = this.resolveChangesRange()
        const data = await http.get('/admin/api/user-logs/consultations', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.filters.keyword || undefined,
            consultationType: this.filters.consultationType || undefined,
            status: this.filters.status || undefined,
            minChanges: changes.minChanges,
            maxChanges: changes.maxChanges,
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
    resolveChangesRange() {
      const range = this.filters.changesRange
      if (!range) return { minChanges: undefined, maxChanges: undefined }
      if (range === '1-3') return { minChanges: 1, maxChanges: 3 }
      if (range === '3-5') return { minChanges: 3, maxChanges: 5 }
      if (range === '5+') return { minChanges: 5, maxChanges: undefined }
      return { minChanges: undefined, maxChanges: undefined }
    },
    async openDetail(row) {
      this.detailDialogVisible = true
      this.detailLoading = true
      this.clearAudioObjectUrl()
      this.audioLoadError = ''
      this.timelineItems = []
      this.detailRecord = row
      try {
        this.detailRecord = await http.get(`/admin/api/user-logs/consultations/${row.idLog}`)
        if (this.detailRecord && this.detailRecord.hasAudio) {
          await this.loadAudio(this.detailRecord.idLog)
        }
        this.loadTimeline(row.idLog)
      } catch (error) {
        this.$message.error(error.message || '加载详情失败')
      } finally {
        this.detailLoading = false
      }
    },
    async loadTimeline(idLog) {
      try {
        this.timelineItems = await http.get(`/admin/api/user-logs/consultations/${idLog}/timeline`)
      } catch (error) {
        this.timelineItems = []
      }
    },
    async loadAudio(idLog) {
      this.audioLoading = true
      this.audioLoadError = ''
      try {
        const blob = await http.get(`/admin/api/user-logs/consultations/${idLog}/audio`, {
          responseType: 'blob'
        })
        this.clearAudioObjectUrl()
        this.audioObjectUrl = URL.createObjectURL(blob)
      } catch (error) {
        this.audioLoadError = error.message || '录音加载失败'
      } finally {
        this.audioLoading = false
      }
    },
    clearAudioObjectUrl() {
      if (this.audioObjectUrl) {
        URL.revokeObjectURL(this.audioObjectUrl)
      }
      this.audioObjectUrl = ''
    },
    handleDetailClosed() {
      this.clearAudioObjectUrl()
      this.audioLoadError = ''
      this.audioLoading = false
      this.timelineItems = []
    },
    normalizeText(value) {
      return normalizeTextValue(value)
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
      showTimelineRaw(item) {
        if (!item) return false
        return this.showRawMeta(item.displayModule, item.module || item.eventType) || this.showRawMeta(item.displayAction, item.action)
      },
    formatDateTime(value) {
      const text = this.normalizeText(value)
      if (!text) return '--'
      return text.replace('T', ' ').replace(/\.\d+$/, '')
    },
    formatBytes(value) {
      const size = Number(value)
      if (!Number.isFinite(size) || size <= 0) return '--'
      if (size < 1024) return `${size} B`
      if (size < 1024 * 1024) return `${(size / 1024).toFixed(1)} KB`
      return `${(size / 1024 / 1024).toFixed(1)} MB`
    },
    consultationTypeMeta(value) {
      const text = this.normalizeText(value)
      const matched = this.consultationTypeOptions.find(item => item.value === text)
      if (matched) return matched
      return { label: text || '--', type: 'info' }
    },
    statusMeta(value, consultationType) {
      const text = this.normalizeText(value)
      if (text === 'completed') {
        return {
          label: this.normalizeText(consultationType) === 'report_interpretation' ? '已完成' : '一键回写',
          type: 'success'
        }
      }
      if (text === 'abandoned') return { label: '放弃', type: 'danger' }
      if (text === 'generated') return { label: '已生成', type: 'info' }
      return { label: text || '--', type: 'info' }
    },
    async handleExport() {
      this.exporting = true
      try {
        const dateRange = Array.isArray(this.filters.dateRange) ? this.filters.dateRange : []
        const changes = this.resolveChangesRange()
        const blob = await http.get('/admin/api/user-logs/consultations/export', {
          params: {
            keyword: this.filters.keyword || undefined,
            consultationType: this.filters.consultationType || undefined,
            status: this.filters.status || undefined,
            minChanges: changes.minChanges,
            maxChanges: changes.maxChanges,
            dateFrom: dateRange[0] || undefined,
            dateTo: dateRange[1] || undefined
          },
          responseType: 'blob'
        })
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url
        link.download = '用户日志_' + new Date().toISOString().slice(0, 10) + '.xlsx'
        document.body.appendChild(link)
        link.click()
        document.body.removeChild(link)
        URL.revokeObjectURL(url)
      } catch (error) {
        this.$message.error(error.message || '导出失败')
      } finally {
        this.exporting = false
      }
    },
    shouldShowSpeechSection(record) {
      if (!record) return false
      return this.normalizeText(record.consultationType) === 'voice' ||
        Boolean(record.hasAudio) ||
        Boolean(record.hasSpeechText) ||
        Boolean(this.normalizeText(record.speechText))
    },
    parsePayload(value) {
      const text = this.normalizeText(value)
      if (!text) return {}
      try {
        const parsed = JSON.parse(text)
        return parsed && typeof parsed === 'object' ? parsed : {}
      } catch (error) {
        return {}
      }
    },
    snapshotRaw(snapshot, key) {
      return snapshot && snapshot[key]
    },
    snapshotText(snapshot, key) {
      return this.displayText(snapshot && snapshot[key])
    },
    snapshotItems(snapshot, key) {
      const value = snapshot && snapshot[key]
      if (Array.isArray(value)) return value
      if (value && typeof value === 'object') return [value]
      if (typeof value === 'string' && value.trim()) {
        return value.split(/[；;、,\n]/).map(item => item.trim()).filter(Boolean)
      }
      return []
    },
    selectedNames(key) {
      const value = this.selectionSnapshot && this.selectionSnapshot[key]
      if (Array.isArray(value) && value.length) {
        return value.join('；')
      }
      return '--'
    }
  }
}
</script>

<style scoped>
.search-input {
  width: 300px;
}

.filter-select {
  width: 150px;
}

.filter-date {
  width: 340px;
}

.patient-meta {
  margin-left: 8px;
  color: #888780;
  font-size: 12px;
}

.mono {
  font-family: var(--font-mono, SFMono-Regular, Menlo, Consolas, monospace);
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 16px;
}

.detail-card,
.selection-card {
  padding: 12px;
  border-radius: 8px;
  background: #F9FCFB;
  border: 1px solid #E8EEEC;
}

.detail-card__label,
.record-field__label {
  margin-bottom: 6px;
  font-size: 12px;
  color: #888780;
}

.detail-card__value {
  color: #2C2C2A;
  font-weight: 500;
  word-break: break-word;
}

.snapshot-compare {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.snapshot-panel {
  border: 1px solid #E8EEEC;
  border-radius: 8px;
  padding: 14px;
  background: #fff;
}

.snapshot-panel__title {
  margin-bottom: 12px;
  color: #2C2C2A;
  font-weight: 600;
}

.record-field {
  margin-bottom: 12px;
}

.record-field__value,
.selection-card__value {
  min-height: 22px;
  color: #2C2C2A;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
}

.record-field__value.multiline {
  max-height: 160px;
  overflow: auto;
  padding: 8px;
  background: #F7F7F4;
  border-radius: 6px;
}

.diff-value {
  white-space: inherit;
}

.diff-removed {
  color: #9B9A94;
  text-decoration: line-through;
  text-decoration-thickness: 1px;
}

.diff-removed + .diff-added {
  margin-left: 8px;
}

.diff-added {
  color: #2C2C2A;
  font-weight: 600;
}

.snapshot-list {
  margin-bottom: 12px;
}

.snapshot-list__items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.snapshot-list__item {
  padding: 8px;
  border-radius: 6px;
  background: #F7F7F4;
}

.snapshot-list__item--removed {
  color: #9B9A94;
  background: #FAF4F1;
  text-decoration: line-through;
  text-decoration-thickness: 1px;
}

.snapshot-list__item--removed .snapshot-list__name,
.snapshot-list__item--removed .snapshot-list__meta {
  color: #9B9A94;
  text-decoration: line-through;
}

.snapshot-list__item--added {
  background: #F1F9F5;
  border-left: 3px solid #67C23A;
}

.snapshot-list__item--added .snapshot-list__name {
  font-weight: 600;
}

::v-deep .diff-value {
  white-space: inherit;
}

::v-deep .diff-removed {
  color: #9B9A94;
  text-decoration: line-through;
  text-decoration-thickness: 1px;
}

::v-deep .diff-removed + .diff-added {
  margin-left: 8px;
}

::v-deep .diff-added {
  color: #2C2C2A;
  font-weight: 600;
}

::v-deep .snapshot-list__items {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

::v-deep .snapshot-list__item {
  padding: 8px;
  border-radius: 6px;
  background: #F7F7F4;
}

::v-deep .snapshot-list__item--removed {
  color: #9B9A94;
  background: #FAF4F1;
  text-decoration: line-through;
  text-decoration-thickness: 1px;
}

::v-deep .snapshot-list__item--removed .snapshot-list__name,
::v-deep .snapshot-list__item--removed .snapshot-list__meta {
  color: #9B9A94;
  text-decoration: line-through;
}

::v-deep .snapshot-list__item--added {
  background: #F1F9F5;
  border-left: 3px solid #67C23A;
}

::v-deep .snapshot-list__item--added .snapshot-list__name {
  font-weight: 600;
}

.snapshot-list__name {
  margin-right: 8px;
  color: #2C2C2A;
  font-weight: 500;
}

.snapshot-list__meta {
  display: block;
  margin-top: 4px;
  color: #888780;
  font-size: 12px;
}

.empty-inline {
  color: #AAA8A0;
}

.inline-tag {
  margin-left: 6px;
}

.speech-section {
  margin-bottom: 16px;
  border: 1px solid #E8EEEC;
  border-radius: 8px;
  padding: 14px;
  background: #fff;
}

.speech-grid {
  display: grid;
  grid-template-columns: minmax(280px, 0.8fr) minmax(0, 1.2fr);
  gap: 12px;
}

.speech-card {
  min-width: 0;
  padding: 12px;
  border-radius: 8px;
  background: #F9FCFB;
  border: 1px solid #E8EEEC;
}

.speech-card--text {
  background: #fff;
}

.audio-meta {
  margin-bottom: 8px;
  color: #5B5A55;
  font-size: 12px;
  line-height: 1.5;
  word-break: break-all;
}

.audio-player {
  display: block;
  width: 100%;
  max-width: 420px;
  height: 36px;
}

.audio-error {
  color: #C45656;
  font-size: 13px;
}

.speech-text {
  min-height: 44px;
  max-height: 180px;
  overflow: auto;
  color: #2C2C2A;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}

.selection-section {
  margin-top: 16px;
  border: 1px solid #E8EEEC;
  border-radius: 8px;
  padding: 14px;
  background: #fff;
}

.selection-grid {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
}

@media (max-width: 960px) {
  .search-input,
  .filter-select,
  .filter-date {
    width: 100%;
  }

  .detail-grid,
  .snapshot-compare,
  .speech-grid,
  .selection-grid {
    grid-template-columns: 1fr;
  }
}

.timeline-section {
  margin-top: 16px;
  border: 1px solid #E8EEEC;
  border-radius: 8px;
  padding: 14px;
  background: #fff;
}

.timeline-content {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-direction: column;
  gap: 4px;
}

.timeline-primary-row {
  display: flex;
  align-items: center;
  gap: 8px;
}

.timeline-module {
  display: inline-block;
  padding: 2px 8px;
  border-radius: 4px;
  background: #F0F2F5;
  color: #5B5A55;
  font-size: 12px;
}

.timeline-action {
  color: #2C2C2A;
  font-size: 13px;
}

.timeline-raw-row {
  font-size: 12px;
  color: #888780;
}
</style>
