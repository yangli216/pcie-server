<template>
  <div class="page-surface assistant-log-page">
    <div v-show="!detailOpen">
      <admin-filter-bar>
        <div class="filter-row">
          <div class="filter-item">
            <div class="filter-label">使用时间</div>
            <time-range-filter v-model="preset" :options="timeOptions" :date-from.sync="dateFrom" :date-to.sync="dateTo" @input="changePreset" @custom-change="changeCustom" />
          </div>
          <div class="filter-item">
            <label for="assistant-doctor" class="filter-label">医生姓名</label>
            <el-input id="assistant-doctor" v-model="doctorName" placeholder="输入医生姓名…" size="small" clearable maxlength="128" @keyup.enter.native="search" />
          </div>
          <div class="filter-item">
            <div class="filter-label">所属机构</div>
            <el-select v-model="organization" placeholder="全部机构" size="small" filterable clearable aria-label="所属机构">
              <el-option v-for="item in organizations" :key="orgKey(item)" :value="orgKey(item)" :label="item.orgName || '未记录机构'" />
            </el-select>
          </div>
          <div class="filter-actions">
            <el-button type="primary" size="small" @click="search">查询</el-button>
            <el-button size="small" @click="reset">重置</el-button>
          </div>
        </div>
        <template #actions>
          <el-button size="small" icon="el-icon-download" :loading="exporting" :disabled="loading || !total" @click="exportCsv">导出 CSV</el-button>
        </template>
      </admin-filter-bar>
      <section class="page-section page-section--table">
        <div class="page-section__header">
          <div>
            <h1 class="page-section__title">医学助手日志</h1>
            <div class="assistant-log-page__hint">记录独立医学助手每次提问与 AI 回答 · 共 {{ total }} 条</div>
          </div>
        </div>
        <el-alert v-if="listError" :title="listError" type="error" :closable="false" show-icon />
        <el-table v-loading="loading" :data="records" empty-text="暂无医学助手对话日志" aria-label="医学助手日志列表">
          <el-table-column label="序号" width="65"><template slot-scope="{ $index }">{{ (current - 1) * size + $index + 1 }}</template></el-table-column>
          <el-table-column prop="orgName" label="机构" min-width="210" show-overflow-tooltip />
          <el-table-column label="医生姓名" min-width="110"><template slot-scope="{ row }">{{ row.doctorName || '未记录' }}</template></el-table-column>
          <el-table-column label="使用时间" width="170"><template slot-scope="{ row }">{{ formatDateTime(row.usageTime) }}</template></el-table-column>
          <el-table-column label="查询内容输入" min-width="280" show-overflow-tooltip>
            <template slot-scope="{ row }">{{ row.inputPreview || (row.imageCount ? '图片提问' : '输入未记录') }}<span v-if="row.imageCount"> [附图 {{ row.imageCount }} 张]</span></template>
          </el-table-column>
          <el-table-column label="状态" width="90"><template slot-scope="{ row }"><status-pill :tone="row.success ? 'success' : 'danger'" :label="row.success ? '成功' : '失败'" /></template></el-table-column>
          <el-table-column label="操作" width="80" fixed="right"><template slot-scope="{ row }"><el-button type="text" size="small" @click="openDetail(row.idLog)">详情</el-button></template></el-table-column>
        </el-table>
        <div class="page-footer"><admin-pagination :current.sync="current" :size.sync="size" :total="total" @change="loadData" /></div>
      </section>
    </div>

    <section v-if="detailOpen" v-loading="detailLoading" class="assistant-log-page__detail" aria-label="医学助手使用详情">
      <div class="assistant-log-page__detail-bar">
        <el-button size="small" icon="el-icon-arrow-left" @click="closeDetail">返回列表</el-button>
        <h1>医学助手使用详情</h1>
      </div>
      <el-alert v-if="detailError" :title="detailError" type="error" :closable="false" show-icon />
      <template v-if="detail">
        <div class="assistant-log-page__meta">
          <div><span>机构</span><strong>{{ detail.orgName || '未记录' }}</strong></div>
          <div><span>医生姓名</span><strong>{{ detail.doctorName || '未记录' }}</strong></div>
          <div><span>使用时间</span><strong>{{ formatDateTime(detail.usageTime) }}</strong></div>
          <div><span>状态</span><status-pill :tone="detail.success ? 'success' : 'danger'" :label="detail.success ? '成功' : '失败'" /></div>
        </div>
        <el-alert v-if="!detail.success" title="本次调用失败，以下回答可能不完整。" type="warning" :closable="false" show-icon />
        <el-alert v-if="!detail.contentAvailable" title="历史日志内容缺失或无法解析。" type="info" :closable="false" show-icon />
        <div class="assistant-log-page__columns">
          <chart-panel title="医生输入内容" :subtitle="`${Array.from(detail.inputText || '').length} 字`">
            <div class="assistant-log-page__text">{{ detail.inputText || '未记录文字输入' }}</div>
            <div v-if="detail.imageCount" class="assistant-log-page__hint">本次附图 {{ detail.imageCount }} 张，日志不展示图片内容。</div>
          </chart-panel>
          <chart-panel title="AI 输出内容" subtitle="仅供临床参考">
            <div class="assistant-log-page__text">{{ detail.outputText || '未记录 AI 回答' }}</div>
          </chart-panel>
        </div>
      </template>
    </section>
  </div>
</template>

<script>
import http from '../api/http'
import { AdminFilterBar, TimeRangeFilter, ChartPanel, StatusPill } from '../components/ui'
import { formatDateTime } from '../utils/admin'
import { assistantLogDateRange } from '../utils/assistantLogDates'

const endpoint = '/admin/api/medical-assistant-logs'
export default {
  name: 'MedicalAssistantLogView',
  components: { AdminFilterBar, TimeRangeFilter, ChartPanel, StatusPill },
  data() {
    return {
      preset: 'week', dateFrom: '', dateTo: '', doctorName: '', organization: '', organizations: [],
      timeOptions: [{ value: 'day', label: '本日' }, { value: 'week', label: '本周' }, { value: 'month', label: '本月' }, { value: 'quarter', label: '本季度' }, { value: 'year', label: '本年度' }, { value: 'custom', label: '自定义' }],
      current: 1, size: 10, total: 0, records: [], applied: null,
      loading: false, exporting: false, listError: '', listSequence: 0,
      detailOpen: false, detail: null, detailLoading: false, detailError: '', detailSequence: 0
    }
  },
  mounted() {
    this.search()
    this.loadOrganizations()
  },
  beforeDestroy() { this.listSequence++; this.detailSequence++ },
  methods: {
    formatDateTime,
    orgKey(item) { return JSON.stringify([item.idOrg || '', item.hisOrgId || '']) },
    async loadOrganizations() {
      try { this.organizations = await http.get(`${endpoint}/organizations`) || [] }
      catch (error) { this.$message.error(`机构选项加载失败：${error.message}`) }
    },
    changePreset() { if (this.preset !== 'custom') this.search() },
    changeCustom() { if (this.dateFrom && this.dateTo) this.search() },
    search() {
      let range
      if (this.preset === 'custom') {
        if (!this.dateFrom || !this.dateTo) return this.$message.warning('请同时选择开始和结束日期')
        if (this.dateFrom > this.dateTo) return this.$message.warning('开始日期不能晚于结束日期')
        range = { dateFrom: this.dateFrom, dateTo: this.dateTo }
      } else range = assistantLogDateRange(this.preset)
      const org = this.organizations.find(item => this.orgKey(item) === this.organization)
      this.applied = { ...range, doctorName: this.doctorName.trim() || undefined, idOrg: org?.idOrg || undefined, hisOrgId: org?.hisOrgId || undefined }
      this.current = 1
      this.loadData()
    },
    reset() {
      this.preset = 'week'; this.dateFrom = ''; this.dateTo = ''; this.doctorName = ''; this.organization = ''
      this.search()
    },
    async loadData() {
      const sequence = ++this.listSequence
      this.loading = true; this.listError = ''; this.records = []
      try {
        const data = await http.get(endpoint, { params: { ...this.applied, current: this.current, size: this.size } })
        if (sequence !== this.listSequence) return
        this.records = data.records || []; this.total = data.total; this.current = data.current; this.size = data.size
      } catch (error) { if (sequence === this.listSequence) { this.listError = error.message; this.total = 0 } }
      finally { if (sequence === this.listSequence) this.loading = false }
    },
    async openDetail(id) {
      const sequence = ++this.detailSequence
      this.detailOpen = true; this.detail = null; this.detailError = ''; this.detailLoading = true
      try {
        const detail = await http.get(`${endpoint}/${encodeURIComponent(id)}`)
        if (sequence === this.detailSequence) this.detail = detail
      } catch (error) { if (sequence === this.detailSequence) this.detailError = error.message }
      finally { if (sequence === this.detailSequence) this.detailLoading = false }
    },
    closeDetail() { this.detailSequence++; this.detailOpen = false; this.detail = null },
    async exportCsv() {
      this.exporting = true
      try {
        const blob = await http.get(`${endpoint}/export`, { params: { ...this.applied }, responseType: 'blob', timeout: 60000 })
        const url = URL.createObjectURL(blob)
        const link = document.createElement('a')
        link.href = url; link.download = `医学助手日志_${this.applied.dateFrom}_${this.applied.dateTo}.csv`
        document.body.appendChild(link); link.click(); link.remove()
        setTimeout(() => URL.revokeObjectURL(url), 1000)
        this.$message.success('已导出当前查询条件下的全部记录')
      } catch (error) { this.$message.error(error.message) }
      finally { this.exporting = false }
    }
  }
}
</script>

<style scoped>
.assistant-log-page__hint { margin-top: 6px; color: var(--color-text-secondary); font-size: 12px; }
.assistant-log-page__detail-bar { display: flex; gap: 16px; align-items: center; margin-bottom: 16px; }
.assistant-log-page h1 { margin: 0; font-size: 17px; font-weight: 600; }
.assistant-log-page__meta { display: flex; flex-wrap: wrap; gap: 18px 32px; padding: 18px 20px; margin-bottom: 16px; border: 1px solid var(--border-color-base); border-radius: 8px; background: #fff; }
.assistant-log-page__meta > div { display: grid; gap: 6px; min-width: 100px; }
.assistant-log-page__meta span { color: var(--color-text-secondary); font-size: 12px; }
.assistant-log-page__meta strong { font-size: 13px; overflow-wrap: anywhere; }
.assistant-log-page__meta .status-pill { justify-self: start; }
.assistant-log-page__columns { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); gap: 16px; margin-top: 16px; }
.assistant-log-page__text { white-space: pre-wrap; overflow-wrap: anywhere; font-size: 14px; line-height: 1.9; color: var(--color-text-primary); }
.assistant-log-page .el-alert { margin-bottom: 12px; }
@media (max-width: 1000px) { .assistant-log-page__columns { grid-template-columns: minmax(0, 1fr); } }
</style>
