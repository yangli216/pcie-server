<template>
  <div class="page-surface patient-memory-page" v-loading="loading">
    <admin-filter-bar>
      <div class="filter-row">
        <div class="filter-item filter-item--keyword">
          <div class="filter-label">患者检索</div>
          <el-input
            v-model.trim="query.keyword"
            clearable
            placeholder="姓名、患者 ID、HIS 机构 ID…"
            class="keyword-input"
            @keyup.enter.native="search"
          />
        </div>
        <div class="filter-item">
          <div class="filter-label">机构</div>
          <el-select v-model="query.idOrg" clearable placeholder="全部机构" class="filter-select" @change="search">
            <el-option v-for="item in orgOptions" :key="item.id" :label="item.name" :value="item.id" />
          </el-select>
        </div>
        <div class="filter-item">
          <div class="filter-label">记忆质量</div>
          <el-select v-model="query.qualityStatus" clearable placeholder="全部状态" class="filter-select" @change="search">
            <el-option label="信息可用" value="fresh" />
            <el-option label="信息不完整" value="partial" />
            <el-option label="存在冲突" value="conflicted" />
          </el-select>
        </div>
        <div class="filter-actions">
          <el-button type="primary" size="small" @click="search">查询</el-button>
          <el-button size="small" @click="reset">重置</el-button>
        </div>
      </div>
    </admin-filter-bar>

    <section class="page-section page-section--table">
      <div class="page-section__header">
        <span class="page-section__title">患者记忆</span>
        <div class="page-section__meta">
          <AdminPagination
            compact
            :total="page.total || 0"
            :size.sync="page.size"
            :current.sync="page.current"
            @change="loadList"
          />
        </div>
      </div>

      <el-table :data="records" size="small" class="admin-table" @row-dblclick="openDetail">
        <el-table-column label="患者" min-width="210">
          <template slot-scope="{ row }">
            <div class="patient-main">
              <span class="patient-name">{{ row.patientName || '未知患者' }}</span>
              <span class="patient-meta">{{ genderLabel(row.patientGender) }} · {{ row.patientAge || '年龄未知' }}</span>
            </div>
            <code-tag :value="row.patientId" />
          </template>
        </el-table-column>
        <el-table-column label="记忆质量" width="116">
          <template slot-scope="{ row }">
            <status-pill :tone="qualityTone(row.qualityStatus)" :label="qualityLabel(row.qualityStatus)" />
          </template>
        </el-table-column>
        <el-table-column label="临床事实" min-width="230">
          <template slot-scope="{ row }">
            <div class="fact-counts">
              <span class="fact-count fact-count--allergy">过敏 {{ row.allergyCount || 0 }}</span>
              <span class="fact-count">诊断 {{ row.diagnosisCount || 0 }}</span>
              <span class="fact-count">用药 {{ row.medicationCount || 0 }}</span>
            </div>
            <div class="table-subtext">共 {{ row.factCount || 0 }} 条 · 冲突 {{ row.conflictCount || 0 }} 条</div>
          </template>
        </el-table-column>
        <el-table-column label="机构范围" min-width="190" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <div>{{ displayOrg(row.idOrg) }}</div>
            <div class="table-subtext">HIS {{ row.idHisOrg || '--' }}</div>
          </template>
        </el-table-column>
        <el-table-column label="记忆版本" width="105">
          <template slot-scope="{ row }"><code-tag :value="`v${row.memoryVersion || 0}`" tone="primary" /></template>
        </el-table-column>
        <el-table-column label="最近同步" width="168">
          <template slot-scope="{ row }">{{ formatDateTime(row.lastSyncTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" align="right">
          <template slot-scope="{ row }">
            <el-button type="text" size="small" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog
      v-if="detailVisible"
      title="患者记忆详情"
      :visible.sync="detailVisible"
      width="1080px"
      top="4vh"
      destroy-on-close
      @closed="closeDetail"
    >
      <div v-loading="detailLoading" class="memory-detail">
        <div v-if="detail.memory" class="memory-detail__summary">
          <div>
            <div class="memory-detail__patient">
              {{ detail.memory.patientName || '未知患者' }}
              <span>{{ genderLabel(detail.memory.patientGender) }} · {{ detail.memory.patientAge || '年龄未知' }}</span>
            </div>
            <div class="memory-detail__identity">
              <code-tag :value="detail.memory.patientId" />
              <span>{{ displayOrg(detail.memory.idOrg) }}</span>
              <span>HIS {{ detail.memory.idHisOrg || '--' }}</span>
            </div>
          </div>
          <div class="memory-detail__status">
            <status-pill :tone="qualityTone(detail.memory.qualityStatus)" :label="qualityLabel(detail.memory.qualityStatus)" />
            <code-tag :value="`v${detail.memory.memoryVersion || 0}`" tone="primary" />
          </div>
        </div>

        <el-tabs v-model="detailTab">
          <el-tab-pane :label="`临床事实 ${detail.facts.length}`" name="facts">
            <el-table :data="detail.facts" size="small" class="admin-table memory-fact-table">
              <el-table-column label="类型" width="96">
                <template slot-scope="{ row }">{{ factTypeLabel(row.factType) }}</template>
              </el-table-column>
              <el-table-column label="事实内容" min-width="230">
                <template slot-scope="{ row }">
                  <div :class="['fact-main', { 'is-suppressed': row.suppressed }]">{{ row.name || row.valueText || row.code || '--' }}</div>
                  <div v-if="row.valueText && row.name" class="table-subtext">{{ row.valueText }}</div>
                  <code-tag v-if="row.code" :value="row.code" />
                </template>
              </el-table-column>
              <el-table-column label="状态 / 可信度" width="170">
                <template slot-scope="{ row }">
                  <div class="fact-status-row">
                    <status-pill :tone="factStatusTone(row)" :label="row.suppressed ? '已屏蔽' : factStatusLabel(row.status)" />
                    <span class="confidence-text">{{ confidenceLabel(row.confidence) }}</span>
                  </div>
                </template>
              </el-table-column>
              <el-table-column label="依据" min-width="240" show-overflow-tooltip>
                <template slot-scope="{ row }">
                  <div>{{ row.evidenceText || '--' }}</div>
                  <div class="table-subtext">{{ sourceLabel(row.sourceType) }} · {{ originLabel(row.origin) }}</div>
                </template>
              </el-table-column>
              <el-table-column label="最近观察" width="158">
                <template slot-scope="{ row }">{{ formatDateTime(row.lastObservedTime) }}</template>
              </el-table-column>
              <el-table-column label="治理" width="142" align="right">
                <template slot-scope="{ row }">
                  <el-button type="text" size="small" @click="openCorrection(row)">纠错</el-button>
                  <el-button v-if="!row.suppressed" type="text" size="small" class="danger-action" @click="suppressFact(row)">屏蔽</el-button>
                  <el-button v-else type="text" size="small" @click="restoreFact(row)">恢复</el-button>
                </template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`来源时间线 ${detail.observations.length}`" name="observations">
            <el-table :data="detail.observations" size="small" class="admin-table">
              <el-table-column label="来源时间" width="170">
                <template slot-scope="{ row }">{{ formatDateTime(row.occurredTime || row.receivedTime) }}</template>
              </el-table-column>
              <el-table-column label="来源" min-width="220">
                <template slot-scope="{ row }">
                  <div>{{ sourceLabel(row.sourceType) }}</div>
                  <div class="table-subtext">{{ row.sourceKey }}</div>
                </template>
              </el-table-column>
              <el-table-column label="就诊 / 事实" min-width="180">
                <template slot-scope="{ row }">
                  <code-tag :value="row.visitId" />
                  <div class="table-subtext">{{ row.factCount || 0 }} 条临床事实</div>
                </template>
              </el-table-column>
              <el-table-column label="版本状态" width="140">
                <template slot-scope="{ row }">
                  <status-pill :tone="row.latest ? 'success' : 'muted'" :label="row.latest ? '当前版本' : '历史版本'" />
                </template>
              </el-table-column>
              <el-table-column label="内容摘要" min-width="210" show-overflow-tooltip>
                <template slot-scope="{ row }"><code-tag :value="shortHash(row.payloadHash)" /></template>
              </el-table-column>
            </el-table>
          </el-tab-pane>

          <el-tab-pane :label="`治理记录 ${detail.audits.length}`" name="audits">
            <el-table :data="detail.audits" size="small" class="admin-table">
              <el-table-column label="操作时间" width="170">
                <template slot-scope="{ row }">{{ formatDateTime(row.operationTime) }}</template>
              </el-table-column>
              <el-table-column label="动作" width="110">
                <template slot-scope="{ row }"><status-pill :tone="auditTone(row.action)" :label="auditLabel(row.action)" /></template>
              </el-table-column>
              <el-table-column label="治理原因" min-width="300" show-overflow-tooltip prop="note" />
              <el-table-column label="操作人" min-width="180">
                <template slot-scope="{ row }">{{ row.operatorName || row.operatorId || '--' }}</template>
              </el-table-column>
              <el-table-column label="事实 ID" min-width="180">
                <template slot-scope="{ row }"><code-tag :value="row.factId" /></template>
              </el-table-column>
            </el-table>
          </el-tab-pane>
        </el-tabs>
      </div>
    </el-dialog>

    <el-dialog
      v-if="correctionVisible"
      title="纠正患者记忆事实"
      :visible.sync="correctionVisible"
      width="520px"
      destroy-on-close
      @closed="resetCorrection"
    >
      <el-form ref="correctionForm" :model="correction" :rules="correctionRules" label-position="top">
        <el-form-item label="事实名称" prop="name">
          <el-input v-model.trim="correction.name" placeholder="请输入医生可读的事实名称…" />
        </el-form-item>
        <el-form-item label="补充内容">
          <el-input v-model.trim="correction.valueText" type="textarea" :rows="2" placeholder="可补充用法、结果或说明…" />
        </el-form-item>
        <div class="correction-grid">
          <el-form-item label="事实状态" prop="status">
            <el-select v-model="correction.status" class="full-width">
              <el-option label="当前有效" value="active" />
              <el-option label="历史记录" value="historical" />
              <el-option label="待核实" value="unknown" />
              <el-option label="存在争议" value="disputed" />
            </el-select>
          </el-form-item>
          <el-form-item label="可信度" prop="confidence">
            <el-select v-model="correction.confidence" class="full-width">
              <el-option label="人工确认" value="confirmed" />
              <el-option label="结构化来源" value="structured" />
              <el-option label="文本提取" value="extracted" />
              <el-option label="低可信" value="low" />
            </el-select>
          </el-form-item>
        </div>
        <el-form-item label="纠错原因" prop="correctionNote">
          <el-input v-model.trim="correction.correctionNote" type="textarea" :rows="3" placeholder="说明依据和原因，保存后进入审计记录…" />
        </el-form-item>
      </el-form>
      <span slot="footer">
        <el-button @click="correctionVisible = false">取消</el-button>
        <el-button type="primary" :loading="correctionSaving" @click="saveCorrection">保存纠错</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import { fetchOrgs } from '../api/reference'
import {
  fetchPatientMemories,
  fetchPatientMemoryDetail,
  restorePatientMemoryFact,
  suppressPatientMemoryFact,
  updatePatientMemoryFact
} from '../api/patientMemory'
import { AdminFilterBar, CodeTag, StatusPill } from '../components/ui'

const FACT_TYPES = {
  allergy: '过敏',
  chronic_condition: '慢病',
  diagnosis: '诊断',
  medication: '用药',
  procedure: '处置',
  lab_result: '检验',
  exam_result: '检查',
  vital: '体征',
  history: '既往史',
  reminder: '提醒'
}

const SOURCE_TYPES = {
  patient_profile: '患者基本信息',
  allergy_snapshot: '过敏史快照',
  visit_summary: '历史就诊摘要',
  outpatient_record: '门诊病历',
  lab_report: '检验报告',
  exam_report: '检查报告',
  doctor_confirmation: '医生确认'
}

export default {
  components: { AdminFilterBar, CodeTag, StatusPill },
  data() {
    return {
      loading: false,
      detailLoading: false,
      records: [],
      page: { current: 1, size: 10, total: 0 },
      query: { keyword: '', idOrg: '', qualityStatus: '' },
      orgOptions: [],
      detailVisible: false,
      detailTab: 'facts',
      detail: { memory: null, facts: [], observations: [], audits: [] },
      correctionVisible: false,
      correctionSaving: false,
      correction: {
        factId: '',
        name: '',
        valueText: '',
        status: 'historical',
        confidence: 'confirmed',
        correctionNote: ''
      },
      correctionRules: {
        name: [{ required: true, message: '请输入事实名称', trigger: 'blur' }],
        status: [{ required: true, message: '请选择事实状态', trigger: 'change' }],
        confidence: [{ required: true, message: '请选择可信度', trigger: 'change' }],
        correctionNote: [{ required: true, message: '请填写纠错原因', trigger: 'blur' }]
      }
    }
  },
  mounted() {
    this.loadRefs()
    this.loadList()
  },
  methods: {
    async loadRefs() {
      try {
        const orgs = await fetchOrgs({ sdStatus: '1' })
        this.orgOptions = (orgs || []).map(item => ({ id: item.idOrg, name: item.naOrg }))
      } catch (error) {
        this.orgOptions = []
      }
    },
    async loadList(page) {
      if (page) this.page.current = page
      this.loading = true
      try {
        const data = await fetchPatientMemories({
          current: this.page.current,
          size: this.page.size,
          ...this.cleanParams(this.query)
        })
        this.records = data.records || []
        this.page.current = Number(data.current || 1)
        this.page.size = Number(data.size || this.page.size)
        this.page.total = Number(data.total || 0)
      } catch (error) {
        this.$message.error((error && error.message) || '加载患者记忆失败')
      } finally {
        this.loading = false
      }
    },
    search() {
      this.page.current = 1
      this.loadList()
    },
    reset() {
      this.query = { keyword: '', idOrg: '', qualityStatus: '' }
      this.search()
    },
    async openDetail(row) {
      if (!row || !row.memoryId) return
      this.detailVisible = true
      this.detailLoading = true
      this.detailTab = 'facts'
      try {
        this.detail = await fetchPatientMemoryDetail(row.memoryId)
      } catch (error) {
        this.$message.error((error && error.message) || '加载患者记忆详情失败')
        this.detailVisible = false
      } finally {
        this.detailLoading = false
      }
    },
    closeDetail() {
      this.detail = { memory: null, facts: [], observations: [], audits: [] }
    },
    openCorrection(row) {
      this.correction = {
        factId: row.factId,
        name: row.name || row.valueText || '',
        valueText: row.valueText || '',
        status: row.status || 'historical',
        confidence: row.confidence || 'confirmed',
        correctionNote: ''
      }
      this.correctionVisible = true
    },
    saveCorrection() {
      this.$refs.correctionForm.validate(async valid => {
        if (!valid || !this.detail.memory) return
        this.correctionSaving = true
        try {
          this.detail = await updatePatientMemoryFact(
            this.detail.memory.memoryId,
            this.correction.factId,
            this.correction
          )
          this.$message.success('患者记忆事实已纠正并记录审计')
          this.correctionVisible = false
          this.loadList()
        } catch (error) {
          this.$message.error((error && error.message) || '保存纠错失败')
        } finally {
          this.correctionSaving = false
        }
      })
    },
    resetCorrection() {
      this.correctionSaving = false
      this.correction = {
        factId: '', name: '', valueText: '', status: 'historical', confidence: 'confirmed', correctionNote: ''
      }
    },
    async suppressFact(row) {
      if (!this.detail.memory) return
      try {
        const result = await this.$prompt('请说明屏蔽原因。屏蔽后该事实不再显示给医生，但原始来源和审计记录会保留。', '屏蔽患者记忆事实', {
          confirmButtonText: '确认屏蔽',
          cancelButtonText: '取消',
          inputType: 'textarea',
          inputPattern: /\S+/,
          inputErrorMessage: '请填写屏蔽原因'
        })
        this.detailLoading = true
        this.detail = await suppressPatientMemoryFact(this.detail.memory.memoryId, row.factId, result.value)
        this.$message.success('该事实已屏蔽')
        this.loadList()
      } catch (error) {
        if (error !== 'cancel' && error !== 'close') {
          this.$message.error((error && error.message) || '屏蔽失败')
        }
      } finally {
        this.detailLoading = false
      }
    },
    async restoreFact(row) {
      if (!this.detail.memory) return
      try {
        const result = await this.$prompt('请说明恢复原因。恢复后该事实会重新进入医生端患者记忆摘要。', '恢复患者记忆事实', {
          confirmButtonText: '确认恢复',
          cancelButtonText: '取消',
          inputPattern: /\S+/,
          inputErrorMessage: '请填写恢复原因'
        })
        this.detailLoading = true
        this.detail = await restorePatientMemoryFact(this.detail.memory.memoryId, row.factId, result.value)
        this.$message.success('该事实已恢复')
        this.loadList()
      } catch (error) {
        if (error !== 'cancel' && error !== 'close') {
          this.$message.error((error && error.message) || '恢复失败')
        }
      } finally {
        this.detailLoading = false
      }
    },
    cleanParams(params) {
      return Object.keys(params).reduce((result, key) => {
        if (params[key] !== '' && params[key] !== null && params[key] !== undefined) result[key] = params[key]
        return result
      }, {})
    },
    displayOrg(idOrg) {
      const item = this.orgOptions.find(org => org.id === idOrg)
      return item ? item.name : (idOrg || '--')
    },
    genderLabel(value) {
      if (value === 'F') return '女'
      if (value === 'M') return '男'
      return '性别未知'
    },
    qualityLabel(value) {
      return { fresh: '信息可用', partial: '信息不完整', conflicted: '存在冲突' }[value] || value || '待评估'
    },
    qualityTone(value) {
      return { fresh: 'success', partial: 'warning', conflicted: 'danger' }[value] || 'muted'
    },
    factTypeLabel(value) {
      return FACT_TYPES[value] || value || '--'
    },
    factStatusLabel(value) {
      return { active: '当前有效', historical: '历史记录', unknown: '待核实', disputed: '存在争议', inactive: '已失效' }[value] || value || '--'
    },
    factStatusTone(row) {
      if (row.suppressed || row.status === 'inactive') return 'muted'
      if (row.status === 'disputed') return 'danger'
      if (row.status === 'unknown') return 'warning'
      return row.status === 'active' ? 'success' : 'muted'
    },
    confidenceLabel(value) {
      return { confirmed: '人工确认', structured: '结构化来源', extracted: '文本提取', low: '低可信' }[value] || value || '--'
    },
    sourceLabel(value) {
      return SOURCE_TYPES[value] || value || '--'
    },
    originLabel(value) {
      return { his: 'HIS事实', doctor: '医生确认', admin: '后台治理' }[value] || value || '--'
    },
    auditLabel(value) {
      return { correct: '纠错', suppress: '屏蔽', restore: '恢复' }[value] || value || '--'
    },
    auditTone(value) {
      return value === 'suppress' ? 'danger' : (value === 'restore' ? 'success' : 'warning')
    },
    shortHash(value) {
      return value ? `${String(value).slice(0, 12)}…` : '--'
    },
    formatDateTime(value) {
      return value ? String(value).replace('T', ' ').slice(0, 19) : '--'
    }
  }
}
</script>

<style scoped>
.patient-memory-page {
  display: grid;
  gap: 16px;
}

.filter-item--keyword {
  min-width: 280px;
}

.keyword-input {
  width: 300px;
}

.patient-main,
.memory-detail__patient,
.memory-detail__identity,
.memory-detail__status,
.fact-counts,
.fact-status-row {
  display: flex;
  align-items: center;
}

.patient-main {
  gap: 8px;
  margin-bottom: 4px;
}

.patient-name {
  color: var(--color-text-primary);
  font-weight: 600;
}

.patient-meta,
.table-subtext,
.confidence-text {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.fact-counts {
  flex-wrap: wrap;
  gap: 6px;
  margin-bottom: 4px;
}

.fact-count {
  padding: 2px 7px;
  border-radius: 10px;
  background: #eef6f4;
  color: #0f766e;
  font-size: 11px;
}

.fact-count--allergy {
  background: #fef2f2;
  color: #b91c1c;
}

.memory-detail {
  min-height: 360px;
}

.memory-detail__summary {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 20px;
  margin-bottom: 18px;
  padding: 16px 18px;
  border: 0.5px solid var(--border-color-base);
  border-radius: 8px;
  background: #f8fafc;
}

.memory-detail__patient {
  gap: 10px;
  color: var(--color-text-primary);
  font-size: 18px;
  font-weight: 600;
}

.memory-detail__patient span {
  color: var(--color-text-secondary);
  font-size: 13px;
  font-weight: 400;
}

.memory-detail__identity,
.memory-detail__status,
.fact-status-row {
  gap: 8px;
}

.memory-detail__identity {
  margin-top: 7px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.fact-main.is-suppressed {
  color: #94a3b8;
  text-decoration: line-through;
}

.danger-action {
  color: #dc2626;
}

.correction-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 14px;
}

.full-width {
  width: 100%;
}

@media (max-width: 760px) {
  .filter-item--keyword,
  .keyword-input {
    width: 100%;
  }

  .memory-detail__summary {
    flex-direction: column;
  }

  .correction-grid {
    grid-template-columns: 1fr;
  }
}
</style>
