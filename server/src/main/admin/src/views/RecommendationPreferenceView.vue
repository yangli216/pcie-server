<template>
  <div class="page-surface recommendation-preference-page" v-loading="loading">
    <admin-filter-bar>
      <div class="filter-row">
        <div class="filter-item">
          <div class="filter-label">时间范围</div>
          <time-range-filter
            v-model="timeRange"
            :options="timeRangeOptions"
            :date-from.sync="query.dateFrom"
            :date-to.sync="query.dateTo"
            @input="setTimeRange"
            @custom-change="onCustomDateChange"
          />
        </div>
        <div class="filter-item">
          <div class="filter-label">区域选择</div>
          <el-select v-model="query.idRegion" placeholder="全部区域" clearable size="small" class="filter-select" @change="handleRegionChange">
            <el-option v-for="item in regionOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </div>
        <div class="filter-item">
          <div class="filter-label">机构选择</div>
          <el-select v-model="query.idOrg" placeholder="全部机构" clearable size="small" class="filter-select" @change="search">
            <el-option v-for="item in filteredOrgOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </div>
        <div class="filter-item">
          <div class="filter-label">推荐类型</div>
          <el-select v-model="query.recommendationType" placeholder="全部类型" clearable size="small" class="filter-select" @change="search">
            <el-option v-for="item in recommendationTypeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </div>
        <div class="filter-item">
          <div class="filter-label">偏好范围</div>
          <el-select v-model="query.scope" placeholder="全部范围" clearable size="small" class="filter-select" @change="search">
            <el-option v-for="item in scopeOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </div>
        <div class="filter-item filter-item--keyword">
          <div class="filter-label">关键词</div>
          <el-input
            v-model.trim="query.keyword"
            clearable
            placeholder="标准项、医生、科室、trace…"
            class="keyword-input"
            @keyup.enter.native="search"
          />
        </div>
        <div class="filter-item">
          <div class="filter-label">科室 ID</div>
          <el-input v-model.trim="query.idDept" clearable placeholder="科室 ID…" class="filter-narrow" @keyup.enter.native="search" />
        </div>
        <div class="filter-item">
          <div class="filter-label">医生 ID</div>
          <el-input v-model.trim="query.idDoctor" clearable placeholder="医生 ID…" class="filter-narrow" @keyup.enter.native="search" />
        </div>
        <div class="filter-actions">
          <el-button type="primary" size="small" @click="search">查询</el-button>
          <el-button size="small" @click="reset">重置</el-button>
        </div>
      </div>
    </admin-filter-bar>

    <div class="metric-grid recommendation-preference-metrics">
      <metric-card label="聚合偏好项" :value="formatNumber(summary.aggregateCount)" desc="已形成偏好分的标准候选项" />
      <metric-card label="原始事件数" :value="formatNumber(summary.eventCount)" desc="医生选择、确认匹配与手动匹配事件" />
      <metric-card label="医生级偏好" :value="formatNumber(summary.doctorScopeCount)" desc="重排时优先使用的医生范围样本" />
      <metric-card label="平均偏好分" :value="formatScore(summary.averagePreferenceScore)" desc="当前筛选条件下的聚合平均值" />
    </div>

    <section class="page-section page-section--table">
      <el-tabs v-model="activeTab" @tab-click="handleTabClick">
        <el-tab-pane label="聚合偏好" name="aggregates">
          <div class="page-section__header recommendation-preference-table-header">
            <span class="page-section__title">聚合偏好</span>
            <div class="page-section__meta">
              <AdminPagination
                compact
                :total="aggregatePage.total || 0"
                :size.sync="aggregatePage.size"
                :current.sync="aggregatePage.current"
                @change="loadAggregates"
              />
            </div>
          </div>
          <el-table :data="aggregateRecords" size="small" class="admin-table">
            <el-table-column label="范围" width="92">
              <template slot-scope="{ row }">
                <status-pill :tone="scopeTone(row.scope)" :label="scopeLabel(row.scope)" />
              </template>
            </el-table-column>
            <el-table-column label="推荐类型" width="108">
              <template slot-scope="{ row }">{{ recommendationTypeLabel(row.recommendationType) }}</template>
            </el-table-column>
            <el-table-column label="标准项" min-width="220" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <div class="item-main">{{ row.itemName || row.itemKey || '--' }}</div>
                <div class="item-sub">
                  <code-tag :value="row.itemCode || row.itemId || row.itemKey" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="机构/科室/医生" min-width="220" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <div class="item-main">{{ displayOrg(row.idOrg) }}</div>
                <div class="item-sub">{{ displayScopeOwner(row) }}</div>
              </template>
            </el-table-column>
            <el-table-column prop="selectedCount" label="选择" width="80" align="right" />
            <el-table-column prop="confirmCount" label="确认" width="80" align="right" />
            <el-table-column prop="manualMatchCount" label="手动" width="80" align="right" />
            <el-table-column prop="sampleCount" label="样本" width="80" align="right" />
            <el-table-column label="偏好分" width="110" align="right">
              <template slot-scope="{ row }">
                <span class="score-text">{{ formatScore(row.preferenceScore) }}</span>
              </template>
            </el-table-column>
            <el-table-column label="最近使用" width="168">
              <template slot-scope="{ row }">{{ formatDateTime(row.lastEventTime) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>

        <el-tab-pane label="原始事件" name="events">
          <div class="page-section__header recommendation-preference-table-header">
            <span class="page-section__title">原始事件</span>
            <div class="page-section__meta">
              <AdminPagination
                compact
                :total="eventPage.total || 0"
                :size.sync="eventPage.size"
                :current.sync="eventPage.current"
                @change="loadEvents"
              />
            </div>
          </div>
          <el-table :data="eventRecords" size="small" class="admin-table">
            <el-table-column label="动作" width="108">
              <template slot-scope="{ row }">
                <status-pill :tone="actionTone(row.actionCode)" :label="actionLabel(row.actionCode)" />
              </template>
            </el-table-column>
            <el-table-column label="推荐类型" width="108">
              <template slot-scope="{ row }">{{ recommendationTypeLabel(row.recommendationType) }}</template>
            </el-table-column>
            <el-table-column label="标准项" min-width="220" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <div class="item-main">{{ row.itemName || row.itemKey || '--' }}</div>
                <div class="item-sub">
                  <code-tag :value="row.itemCode || row.itemId || row.itemKey" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="医生/科室" min-width="180" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <div class="item-main">{{ row.naDoctor || row.idDoctor || '--' }}</div>
                <div class="item-sub">{{ row.naDept || row.idDept || '--' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="标记" width="120">
              <template slot-scope="{ row }">
                <div class="flag-row">
                  <status-pill :tone="row.selected ? 'success' : 'muted'" :label="row.selected ? '选中' : '未选'" />
                  <status-pill v-if="row.primary" tone="warning" label="主诊断" />
                </div>
              </template>
            </el-table-column>
            <el-table-column label="来源" min-width="180" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <div class="item-main">{{ row.sourceModule || '--' }}</div>
                <div class="item-sub">{{ row.sceneCode || '--' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="trace/session" min-width="210" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <div class="item-main"><code-tag :value="row.traceId" /></div>
                <div class="item-sub">{{ row.consultationId || row.sessionId || '--' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="版本" min-width="190" show-overflow-tooltip>
              <template slot-scope="{ row }">
                <div class="item-main">{{ row.modelVersion || '--' }}</div>
                <div class="item-sub">{{ row.promptVersion || row.templateVersion || '--' }}</div>
              </template>
            </el-table-column>
            <el-table-column label="事件时间" width="168">
              <template slot-scope="{ row }">{{ formatDateTime(row.eventTime) }}</template>
            </el-table-column>
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </section>
  </div>
</template>

<script>
import { activeRefOptions } from '../api/reference'
import {
  fetchRecommendationPreferenceAggregates,
  fetchRecommendationPreferenceEvents,
  fetchRecommendationPreferenceSummary
} from '../api/recommendationPreference'
import { AdminFilterBar, CodeTag, MetricCard, StatusPill, TimeRangeFilter } from '../components/ui'

const TIME_RANGES = [
  { value: 'today', label: '今日' },
  { value: 'week', label: '本周' },
  { value: 'month', label: '本月' },
  { value: 'quarter', label: '本季度' },
  { value: 'year', label: '本年' },
  { value: 'custom', label: '自定义' }
]

const RECOMMENDATION_TYPES = [
  { value: 'diagnosis', label: '诊断' },
  { value: 'medicine', label: '用药' },
  { value: 'exam', label: '检查' },
  { value: 'lab_test', label: '检验' },
  { value: 'procedure', label: '处置' }
]

const SCOPES = [
  { value: 'doctor', label: '医生级' },
  { value: 'dept', label: '科室级' },
  { value: 'org', label: '机构级' }
]

const ACTIONS = {
  final_select: { label: '最终选择', tone: 'success' },
  manual_match: { label: '手动匹配', tone: 'warning' },
  confirm_match: { label: '确认匹配', tone: 'success' }
}

export default {
  components: {
    AdminFilterBar,
    CodeTag,
    MetricCard,
    StatusPill,
    TimeRangeFilter
  },
  data() {
    return {
      loading: false,
      activeTab: 'aggregates',
      timeRange: 'month',
      timeRangeOptions: TIME_RANGES,
      recommendationTypeOptions: RECOMMENDATION_TYPES,
      scopeOptions: SCOPES,
      query: {
        dateFrom: '',
        dateTo: '',
        idRegion: '',
        idOrg: '',
        recommendationType: '',
        scope: '',
        keyword: '',
        idDept: '',
        idDoctor: ''
      },
      summary: {},
      aggregateRecords: [],
      eventRecords: [],
      aggregatePage: { current: 1, size: 10, total: 0 },
      eventPage: { current: 1, size: 10, total: 0 },
      eventLoaded: false,
      regionOptions: [],
      orgOptions: []
    }
  },
  computed: {
    filteredOrgOptions() {
      if (!this.query.idRegion) {
        return this.orgOptions
      }
      return this.orgOptions.filter(item => item.idRegion === this.query.idRegion)
    }
  },
  mounted() {
    this.initDateRange()
    this.loadRefs()
    this.search()
  },
  methods: {
    initDateRange() {
      const now = new Date()
      let from
      let to
      switch (this.timeRange) {
        case 'today':
          from = to = this.formatDate(now)
          break
        case 'week': {
          const day = now.getDay() || 7
          from = this.formatDate(new Date(now.getFullYear(), now.getMonth(), now.getDate() - day + 1))
          to = this.formatDate(now)
          break
        }
        case 'quarter': {
          const quarterStart = Math.floor(now.getMonth() / 3) * 3
          from = this.formatDate(new Date(now.getFullYear(), quarterStart, 1))
          to = this.formatDate(now)
          break
        }
        case 'year':
          from = this.formatDate(new Date(now.getFullYear(), 0, 1))
          to = this.formatDate(now)
          break
        case 'month':
        default:
          from = this.formatDate(new Date(now.getFullYear(), now.getMonth(), 1))
          to = this.formatDate(now)
      }
      this.query.dateFrom = from
      this.query.dateTo = to
    },
    formatDate(date) {
      return `${date.getFullYear()}-${String(date.getMonth() + 1).padStart(2, '0')}-${String(date.getDate()).padStart(2, '0')}`
    },
    async loadRefs() {
      try {
        const refs = await activeRefOptions()
        this.regionOptions = (refs.regions || []).map(item => ({
          id: item.idRegion,
          name: item.naRegion
        }))
        this.orgOptions = (refs.orgs || []).map(item => ({
          id: item.idOrg,
          name: item.naOrg,
          idRegion: item.idRegion
        }))
      } catch (error) {
        this.regionOptions = []
        this.orgOptions = []
      }
    },
    setTimeRange(value) {
      this.timeRange = value
      this.initDateRange()
      this.search()
    },
    onCustomDateChange() {
      if (this.query.dateFrom && this.query.dateTo) {
        this.search()
      }
    },
    handleRegionChange() {
      const selectedOrg = this.orgOptions.find(item => item.id === this.query.idOrg)
      if (this.query.idRegion && selectedOrg && selectedOrg.idRegion !== this.query.idRegion) {
        this.query.idOrg = ''
      }
      this.search()
    },
    handleTabClick(tab) {
      const name = tab && tab.name ? tab.name : this.activeTab
      if (name === 'events' && !this.eventLoaded) {
        this.loadEvents()
      }
    },
    async search() {
      this.aggregatePage.current = 1
      this.eventPage.current = 1
      this.eventPage.total = 0
      this.eventRecords = []
      this.eventLoaded = false
      await this.loadAll()
    },
    async loadAll() {
      this.loading = true
      try {
        const params = this.buildParams()
        const requests = [
          fetchRecommendationPreferenceSummary(params),
          fetchRecommendationPreferenceAggregates({
            ...params,
            current: this.aggregatePage.current,
            size: this.aggregatePage.size
          })
        ]
        if (this.activeTab === 'events') {
          requests.push(fetchRecommendationPreferenceEvents({
            ...params,
            current: this.eventPage.current,
            size: this.eventPage.size
          }))
        }
        const responses = await Promise.all(requests)
        this.summary = responses[0] || {}
        this.applyAggregatePage(responses[1] || {})
        if (responses[2]) {
          this.applyEventPage(responses[2] || {})
          this.eventLoaded = true
        }
      } catch (error) {
        this.$message.error((error && error.message) || '加载推荐偏好失败')
      } finally {
        this.loading = false
      }
    },
    async loadAggregates(page) {
      if (page) {
        this.aggregatePage.current = page
      }
      this.loading = true
      try {
        const data = await fetchRecommendationPreferenceAggregates({
          ...this.buildParams(),
          current: this.aggregatePage.current,
          size: this.aggregatePage.size
        })
        this.applyAggregatePage(data || {})
      } catch (error) {
        this.$message.error((error && error.message) || '加载聚合偏好失败')
      } finally {
        this.loading = false
      }
    },
    async loadEvents(page) {
      if (page) {
        this.eventPage.current = page
      }
      this.loading = true
      try {
        const data = await fetchRecommendationPreferenceEvents({
          ...this.buildParams(),
          current: this.eventPage.current,
          size: this.eventPage.size
        })
        this.applyEventPage(data || {})
        this.eventLoaded = true
      } catch (error) {
        this.$message.error((error && error.message) || '加载原始事件失败')
      } finally {
        this.loading = false
      }
    },
    applyAggregatePage(data) {
      this.aggregateRecords = data.records || []
      this.aggregatePage.current = Number(data.current || this.aggregatePage.current || 1)
      this.aggregatePage.size = Number(data.size || this.aggregatePage.size || 10)
      this.aggregatePage.total = Number(data.total || 0)
    },
    applyEventPage(data) {
      this.eventRecords = data.records || []
      this.eventPage.current = Number(data.current || this.eventPage.current || 1)
      this.eventPage.size = Number(data.size || this.eventPage.size || 10)
      this.eventPage.total = Number(data.total || 0)
    },
    buildParams() {
      const params = {
        dateFrom: this.query.dateFrom,
        dateTo: this.query.dateTo,
        idRegion: this.query.idRegion,
        idOrg: this.query.idOrg,
        idDept: this.query.idDept,
        idDoctor: this.query.idDoctor,
        recommendationType: this.query.recommendationType,
        scope: this.query.scope,
        keyword: this.query.keyword
      }
      Object.keys(params).forEach(key => {
        if (params[key] === '' || params[key] === null || params[key] === undefined) {
          delete params[key]
        }
      })
      return params
    },
    reset() {
      this.timeRange = 'month'
      this.query = {
        dateFrom: '',
        dateTo: '',
        idRegion: '',
        idOrg: '',
        recommendationType: '',
        scope: '',
        keyword: '',
        idDept: '',
        idDoctor: ''
      }
      this.initDateRange()
      this.search()
    },
    displayOrg(idOrg) {
      const org = this.orgOptions.find(item => item.id === idOrg)
      return org ? org.name : (idOrg || '--')
    },
    displayScopeOwner(row) {
      if (row.scope === 'doctor') {
        return `医生 ${row.idDoctor || '--'} / 科室 ${row.idDept || '--'}`
      }
      if (row.scope === 'dept') {
        return `科室 ${row.idDept || '--'}`
      }
      return '机构级'
    },
    recommendationTypeLabel(value) {
      const item = this.recommendationTypeOptions.find(option => option.value === value)
      return item ? item.label : (value || '--')
    },
    scopeLabel(value) {
      const item = this.scopeOptions.find(option => option.value === value)
      return item ? item.label : (value || '--')
    },
    scopeTone(value) {
      if (value === 'doctor') {
        return 'success'
      }
      if (value === 'dept') {
        return 'warning'
      }
      return 'muted'
    },
    actionLabel(value) {
      return (ACTIONS[value] && ACTIONS[value].label) || value || '--'
    },
    actionTone(value) {
      return (ACTIONS[value] && ACTIONS[value].tone) || 'muted'
    },
    formatNumber(value) {
      return Number(value || 0).toLocaleString()
    },
    formatScore(value) {
      const number = Number(value || 0)
      if (Number.isNaN(number)) {
        return '0.0000'
      }
      return number.toFixed(4)
    },
    formatDateTime(value) {
      if (!value) {
        return '--'
      }
      return String(value).replace('T', ' ').slice(0, 19)
    }
  }
}
</script>

<style scoped>
.recommendation-preference-page {
  display: grid;
  gap: 16px;
}

.recommendation-preference-metrics {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.filter-item--keyword {
  min-width: 240px;
}

.keyword-input {
  width: 260px;
}

.recommendation-preference-table-header {
  margin: 6px 0 14px;
}

.item-main {
  min-width: 0;
  color: var(--color-text-primary);
  line-height: 1.5;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.item-sub {
  min-width: 0;
  margin-top: 2px;
  color: var(--color-text-secondary);
  font-size: 12px;
  line-height: 1.4;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.flag-row {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: max-content;
}

.score-text {
  color: var(--color-primary);
  font-variant-numeric: tabular-nums;
  font-weight: 500;
}

@media (max-width: 1200px) {
  .recommendation-preference-metrics {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 720px) {
  .recommendation-preference-metrics {
    grid-template-columns: 1fr;
  }

  .keyword-input {
    width: 100%;
  }

  .filter-item--keyword {
    width: 100%;
  }
}
</style>
