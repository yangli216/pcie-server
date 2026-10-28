<template>
  <div class="qc-results">
    <template v-if="qc">
      <div class="qc-results__overview">
        <div class="qc-results__score"><strong>{{ displayScore == null ? '--' : displayScore }}</strong><span>/ {{ total }} 分</span></div>
        <div class="qc-results__counts">
          <span>问题 <b>{{ displayFindingCount }}</b></span>
          <span :class="{ 'qc-results__high': displayHighSeverityCount > 0 }">重点 <b>{{ displayHighSeverityCount }}</b></span>
          <status-pill v-if="editing" tone="warning" label="调整中" />
          <status-pill v-else-if="qc.resultSource === 'manual'" tone="warning" label="人工调整" />
        </div>
        <details class="qc-results__metadata">
          <summary>质控信息</summary>
          <div>{{ formatDateTime(qc.analyzedAt) }} · {{ qc.model || '未记录模型' }} · {{ qc.ruleVersion || '未记录规则版本' }}</div>
          <div v-if="qc.resultSource === 'manual'">
            人工调整：{{ qc.reviewedByName || '未记录' }} · {{ formatDateTime(qc.reviewedAt) }} · AI 原始总分 {{ qc.aiScore == null ? '--' : qc.aiScore }}
          </div>
          <div v-if="qc.resultSource === 'manual' && qc.reviewReason">理由：{{ qc.reviewReason }}</div>
        </details>
      </div>
      <p class="qc-results__notice">{{ editing ? '正在人工调整，仅修改本平台书写参考，不回写 PHIS；保存时必须填写调整理由。' : (qc.resultSource === 'manual' ? '本结果已经人工调整，仅作书写参考，不代表质控通过或医学审核通过。' : '仅作 AI 书写参考，不代表质控通过或医学审核通过。') }}</p>
      <div class="qc-results__body" tabindex="0" aria-label="质控参考内容">
        <details v-if="displayDimensions.length" class="qc-results__dimensions" :open="editing">
          <summary>维度评分（{{ displayDimensions.length }} 项，点击展开）</summary>
          <div class="qc-results__dimension-grid">
            <div v-for="dimension in displayDimensions" :key="dimension.code" class="qc-results__dimension">
              <div class="qc-results__dimension-head">
                <span>{{ dimension.name }} <small>满分 {{ dimension.weight }}</small></span>
                <el-input-number
                  v-if="editing"
                   :value="dimension.score"
                  class="qc-results__dimension-input"
                  :min="0"
                  :max="dimension.weight"
                  :precision="0"
                  :controls="false"
                  size="mini"
                  :aria-label="`${dimension.name}得分`"
                  @change="adjustDimension(dimension, $event)"
                />
                <b v-else>{{ dimension.score == null ? '--' : dimension.score }}</b>
              </div>
              <div class="qc-results__meter" aria-hidden="true"><span :style="{ width: `${dimensionPercent(dimension.score, dimension.weight)}%` }"></span></div>
              <el-input v-if="editing" v-model="dimension.summary" class="qc-results__dimension-summary" size="mini" maxlength="200" placeholder="维度说明（可选）" />
              <p v-else-if="dimension.summary" :title="dimension.summary">{{ dimension.summary }}</p>
            </div>
          </div>
        </details>
        <div class="qc-results__toolbar">
          <div class="qc-results__toolbar-title">
            <h3>问题明细 <span>{{ findings.length }}</span></h3>
            <button v-if="editing" type="button" class="qc-results__link" @click="addFinding">添加问题</button>
            <span v-else class="qc-results__toolbar-hint">点击问题关联病历</span>
          </div>
          <label>严重程度
            <select v-model="severity" aria-label="按严重程度筛选问题">
              <option value="all">全部</option><option value="high">重点</option><option value="medium">中</option><option value="low">低</option><option value="info">提示</option>
            </select>
          </label>
        </div>
        <div v-if="findings.length" class="qc-results__findings">
          <article
            v-for="(finding, index) in findings"
            :key="editing ? finding._key : finding.originalIndex"
            class="qc-results__finding"
            :class="[
              `qc-results__finding--${finding.severity}`,
              {
                'qc-results__finding--ignored': finding.ignored,
                'qc-results__finding--interactive': !editing,
                'qc-results__finding--editing': editing,
                'qc-results__finding--selected': activeFindingKey === findingKey(finding)
              }
            ]"
            :tabindex="editing ? null : 0"
            :role="editing ? null : 'button'"
            :aria-current="!editing && activeFindingKey === findingKey(finding) ? 'true' : null"
            @click="selectFinding(finding)"
            @keydown.enter.prevent="selectFinding(finding)"
            @keydown.space.prevent="selectFinding(finding)"
          >
            <template v-if="editing">
              <div class="qc-results__finding-head qc-results__finding-edit-head">
                <span class="qc-results__number">{{ index + 1 }}.</span>
                <select v-model="finding.severity" aria-label="问题严重程度">
                  <option value="high">重点</option><option value="medium">中</option><option value="low">低</option><option value="info">提示</option>
                </select>
                <select
                  :value="finding.category"
                  class="qc-results__category-select"
                  aria-label="问题所属评分维度"
                  @change="changeCategory(finding, $event.target.value)"
                >
                  <option v-for="dimension in draftDimensions" :key="dimension.code" :value="dimension.name">{{ dimension.name }}</option>
                </select>
                <label class="qc-results__deduction-control">扣分
                  <el-input-number
                    :value="finding.deduction"
                    class="qc-results__deduction-input"
                    :min="0"
                    :max="dimensionWeight(finding.category)"
                    :precision="0"
                    :controls="false"
                    :disabled="finding.ignored"
                    size="mini"
                    aria-label="问题扣分"
                    @input="updateDeduction(finding, $event)"
                  />
                </label>
                <button type="button" class="qc-results__link" :aria-label="finding.ignored ? '恢复该问题' : '忽略该问题'" @click="toggleIgnored(finding)">{{ finding.ignored ? '恢复' : '忽略' }}</button>
                <button type="button" class="qc-results__link qc-results__delete" aria-label="删除该问题" @click="removeFinding(finding)">删除</button>
                <button type="button" class="qc-results__link qc-results__locate" @click="selectFinding(finding, true)">定位病历</button>
              </div>
              <div class="qc-results__edit-fields">
                <label>章节<el-input v-model="finding.sectionName" size="mini" maxlength="128" /></label>
                <label>问题说明（必填）<el-input v-model="finding.issue" type="textarea" :rows="2" maxlength="1000" /></label>
                <label>修改建议<el-input v-model="finding.suggestion" size="mini" maxlength="1000" /></label>
              </div>
              <details v-if="finding.excerpt" class="qc-results__excerpt"><summary>原文摘录</summary><blockquote>{{ finding.excerpt }}</blockquote></details>
            </template>
            <template v-else>
              <div class="qc-results__finding-head">
                <span class="qc-results__number">{{ index + 1 }}.</span>
                <status-pill :tone="severityTone(finding.severity)" :label="severityLabel(finding.severity)" />
                <span>{{ findingLocation(finding) }}</span>
                <span class="qc-results__deduction">{{ finding.deduction ? `扣 ${finding.deduction} 分` : '提示，不计分' }}</span>
                <status-pill v-if="finding.ignored" tone="muted" label="已忽略" />
                <span v-if="activeFindingKey === findingKey(finding)" class="qc-results__relation">已关联病历</span>
              </div>
              <div class="qc-results__finding-content" :class="{ 'qc-results__finding-content--single': !finding.suggestion }">
                <p class="qc-results__issue">{{ finding.issue }}</p>
                <p v-if="finding.suggestion" class="qc-results__suggestion"><span>建议</span>{{ finding.suggestion }}</p>
                <details v-if="finding.excerpt" class="qc-results__excerpt"><summary>原文摘录</summary><blockquote>{{ finding.excerpt }}</blockquote></details>
              </div>
            </template>
          </article>
        </div>
        <p v-else class="qc-results__empty">{{ allFindings.length ? '当前严重程度下没有问题。' : (editing ? '无问题明细，可点击「添加问题」新增。' : '本次分析未发现可识别的书写问题。') }}</p>
      </div>
      <div v-if="editing" class="qc-results__review-footer">
        <el-input
          v-model="reason"
          type="textarea"
          :rows="2"
          maxlength="500"
          show-word-limit
          placeholder="调整理由（必填）"
          aria-label="调整理由"
        />
        <div class="qc-results__review-actions">
          <el-button size="small" :disabled="saving" @click="cancel">取消</el-button>
          <el-button size="small" type="primary" :loading="saving" @click="submit">保存调整</el-button>
        </div>
      </div>
    </template>
    <div v-else class="qc-results__empty">尚未质控，点击右上角「立即质控」查看书写参考。</div>
  </div>
</template>

<script>
import StatusPill from '../ui/StatusPill.vue'
import { formatDateTime } from '../../utils/admin'
import {
  dimensionPercent,
  findingLocation,
  recalculateScores,
  findingCounts,
  orderedFindings,
  setFindingCategory,
  setFindingIgnored,
  totalScore,
  totalWeight,
  updateFindingDeduction
} from '../../utils/emrQcResults'

const SEVERITIES = ['high', 'medium', 'low', 'info']

export default {
  name: 'EmrQcResultPanel',
  components: { StatusPill },
  props: {
    qc: { type: Object, default: null },
    editing: { type: Boolean, default: false },
    saving: { type: Boolean, default: false },
    activeFindingKey: { type: [String, Number], default: null }
  },
  data() {
    return {
      severity: 'all',
      draftDimensions: [],
      draftFindings: [],
      reason: '',
      nextFindingKey: 1
    }
  },
  computed: {
    displayDimensions() { return this.editing ? this.draftDimensions : ((this.qc && this.qc.dimensions) || []) },
    allFindings() { return this.editing ? this.draftFindings : orderedFindings(this.qc) },
    findings() {
      if (!this.editing) return orderedFindings(this.qc, this.severity)
      return this.draftFindings.filter(item => this.severity === 'all' || item.severity === this.severity)
    },
    total() { return totalWeight({ dimensions: this.displayDimensions }) },
    displayScore() { return this.editing ? totalScore({ dimensions: this.draftDimensions }) : this.qc.score },
    displayFindingCount() {
      if (this.editing) return findingCounts(this.draftFindings).total
      return this.qc.findingCount == null ? findingCounts(this.qc.findings).total : this.qc.findingCount
    },
    displayHighSeverityCount() {
      if (this.editing) return findingCounts(this.draftFindings).high
      return this.qc.highSeverityCount == null ? findingCounts(this.qc.findings).high : this.qc.highSeverityCount
    }
  },
  watch: {
    qc() {
      this.severity = 'all'
      this.$emit('update:editing', false)
    },
    editing(value) {
      if (value) this.resetDraft()
    }
  },
  methods: {
    formatDateTime,
    dimensionPercent,
    findingLocation,
    findingKey(finding) { return this.editing ? `draft-${finding._key}` : `finding-${finding.originalIndex}` },
    selectFinding(finding, force = false) {
      if (this.editing && !force) return
      this.$emit('locate', { finding, key: this.findingKey(finding) })
    },
    severityTone(value) { return { high: 'danger', medium: 'warning' }[value] || 'muted' },
    severityLabel(value) { return { high: '重点', medium: '中', low: '低', info: '提示' }[value] || '提示' },
    resetDraft() {
      const qc = this.qc || {}
      this.draftDimensions = (qc.dimensions || []).map(item => ({
        code: item.code,
        name: item.name,
        weight: item.weight,
        score: item.score == null ? 0 : item.score,
        summary: item.summary || ''
      }))
      this.nextFindingKey = 1
      this.draftFindings = (qc.findings || []).map(item => {
        const dimension = this.draftDimensions.find(candidate => candidate.code === item.category || candidate.name === item.category)
        const fallback = this.draftDimensions.find(candidate => candidate.code === 'other') || this.draftDimensions[0]
        return {
          severity: SEVERITIES.includes(item.severity) ? item.severity : 'info',
          category: dimension ? dimension.name : (fallback ? fallback.name : '其他'),
          ruleCode: item.ruleCode || null,
          sectionName: item.sectionName || '',
          excerpt: item.excerpt || '',
          issue: item.issue || '',
          suggestion: item.suggestion || '',
          deduction: item.deduction == null ? 0 : item.deduction,
          ignored: Boolean(item.ignored),
          _key: this.nextFindingKey++
        }
      })
      this.reason = ''
    },
    dimensionWeight(category) {
      const dimension = this.draftDimensions.find(item => item.code === category || item.name === category)
      return dimension ? dimension.weight : 0
    },
    updateDeduction(finding, value) {
      updateFindingDeduction(this.draftDimensions, finding, value, this.draftFindings)
    },
    toggleIgnored(finding) {
      setFindingIgnored(this.draftDimensions, finding, !finding.ignored, this.draftFindings)
    },
    changeCategory(finding, category) {
      setFindingCategory(this.draftDimensions, finding, category, this.draftFindings)
    },
    adjustDimension(dimension, value) {
      const target = Math.max(0, Math.min(dimension.weight, Math.round(Number(value))))
      if (!Number.isFinite(target)) return
      let adjustment = this.draftFindings.find(item => item.ruleCode === 'MANUAL-SCORE' && item.category === dimension.name)
      const other = this.draftFindings.filter(item => item !== adjustment && !item.ignored && item.category === dimension.name)
        .reduce((sum, item) => sum + (Number(item.deduction) || 0), 0)
      if (other > dimension.weight - target) {
        this.$message.warning('请先减少或忽略该维度的问题扣分，再提高得分')
        this.$forceUpdate()
        return
      }
      if (!adjustment) {
        adjustment = { severity: 'info', category: dimension.name, ruleCode: 'MANUAL-SCORE', sectionName: '', excerpt: '', issue: '人工调整维度扣分', suggestion: '', deduction: 0, ignored: false, _key: this.nextFindingKey++ }
        this.draftFindings.push(adjustment)
      }
      adjustment.ignored = false
      adjustment.deduction = dimension.weight - target - other
      recalculateScores(this.draftDimensions, this.draftFindings)
    },
    addFinding() {
      const fallback = this.draftDimensions.find(item => item.code === 'other') || this.draftDimensions[0]
      this.draftFindings.push({
        severity: 'low',
        category: fallback ? fallback.name : '其他',
        ruleCode: null,
        sectionName: '',
        excerpt: '',
        issue: '',
        suggestion: '',
        deduction: 0,
        ignored: false,
        _key: this.nextFindingKey++
      })
    },
    removeFinding(finding) {
      const index = this.draftFindings.findIndex(item => item._key === finding._key)
      if (index >= 0) this.draftFindings.splice(index, 1)
      recalculateScores(this.draftDimensions, this.draftFindings)
    },
    async cancel() {
      try { await this.$confirm('确定放弃本次未保存的调整？', '未保存的调整', { type: 'warning' }) } catch (_) { return }
      this.$emit('update:editing', false)
    },
    submit() {
      if (!this.reason.trim()) {
        this.$message.warning('请填写调整理由')
        return
      }
      if (this.draftFindings.some(item => !item.issue || !item.issue.trim())) {
        this.$message.warning('问题说明不能为空，请补充或删除空白问题')
        return
      }
      this.$emit('save', {
        reason: this.reason.trim(),
        baseAnalysisId: this.qc.analysisId,
        dimensions: this.draftDimensions.map(item => ({ code: item.code, score: item.score, summary: item.summary })),
        findings: this.draftFindings.map(item => ({
          severity: item.severity,
          category: item.category,
          ruleCode: item.ruleCode,
          sectionName: item.sectionName,
          excerpt: item.excerpt,
          issue: item.issue,
          suggestion: item.suggestion,
          deduction: item.deduction,
          ignored: Boolean(item.ignored)
        }))
      })
    }
  }
}
</script>

<style scoped>
.qc-results { display: flex; flex: 1; flex-direction: column; min-height: 0; min-width: 0; color: var(--color-text-primary); }
.qc-results__overview { display: flex; flex-wrap: wrap; align-items: baseline; gap: 8px 18px; flex-shrink: 0; }
.qc-results__score { display: flex; align-items: baseline; gap: 5px; }
.qc-results__score strong { font-size: 30px; line-height: 1.1; font-variant-numeric: tabular-nums; }
.qc-results__score span, .qc-results__counts, .qc-results__metadata { font-size: 12px; color: var(--color-text-secondary); }
.qc-results__counts { display: flex; gap: 14px; }
.qc-results__counts b { margin-left: 4px; font-size: 16px; color: var(--color-text-primary); }
.qc-results__counts .qc-results__high, .qc-results__high b { color: #b64238; }
.qc-results__metadata { margin-left: auto; max-width: 100%; }
.qc-results__metadata div { padding-top: 4px; overflow-wrap: anywhere; }
.qc-results summary { cursor: pointer; font-size: 12px; }
.qc-results summary:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.qc-results__notice { margin: 4px 0 6px; color: var(--color-text-secondary); font-size: 12px; line-height: 1.5; flex-shrink: 0; }
.qc-results__body { flex: 1; min-height: 0; overflow: auto; overscroll-behavior: contain; scrollbar-gutter: stable; padding-right: 6px; overflow-wrap: anywhere; }
.qc-results__dimensions > summary { color: var(--color-text-secondary); font-size: 12px; }
.qc-results__dimensions[open] > summary { margin-bottom: 7px; }
.qc-results__dimension-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 6px; }
.qc-results__dimension { min-width: 0; padding: 7px 9px; background: #f5f8f7; border-radius: 4px; }
.qc-results__dimension-head { display: flex; gap: 6px; align-items: baseline; justify-content: space-between; font-size: 12px; }
.qc-results__dimension-head small { font-size: 10px; color: var(--color-text-secondary); }
.qc-results__dimension-head b { font-size: 15px; font-variant-numeric: tabular-nums; }
.qc-results__dimension p { margin: 4px 0 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.5; }
.qc-results__meter { height: 3px; margin-top: 5px; background: #e1e9e5; border-radius: 2px; overflow: hidden; }
.qc-results__meter span { display: block; height: 100%; background: var(--color-primary); }
.qc-results__toolbar { position: sticky; top: 0; z-index: 2; display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 6px; padding: 7px 0 6px; background: #fff; border-bottom: 1px solid var(--border-color-base); }
.qc-results__toolbar h3 { margin: 0; font-size: 14px; font-weight: 600; }
.qc-results__toolbar h3 span { margin-left: 4px; font-size: 12px; color: var(--color-text-secondary); }
.qc-results__toolbar label { display: flex; align-items: center; gap: 6px; font-size: 11px; color: var(--color-text-secondary); }
.qc-results__toolbar select { padding: 3px 6px; border: 1px solid var(--border-color-base); border-radius: 4px; background: #fff; color: var(--color-text-primary); font-size: 12px; }
.qc-results__findings { display: grid; gap: 6px; padding: 6px 1px 4px; }
.qc-results__finding { position: relative; padding: 8px 10px; border: 1px solid var(--border-color-light); border-left: 3px solid #b8c4bf; border-radius: 6px; background: #fff; }
.qc-results__finding--high { border-left-color: #c65349; }
.qc-results__finding--medium { border-left-color: #c58a31; }
.qc-results__finding--low { border-left-color: #6e9485; }
.qc-results__finding--interactive { cursor: pointer; }
.qc-results__finding--interactive:hover { border-color: #9bbbad; background: #f8fbfa; }
.qc-results__finding--interactive:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.qc-results__finding--selected { border-color: var(--color-primary); background: #f2faf7; box-shadow: 0 0 0 2px rgba(47, 157, 120, 0.14); }
.qc-results__finding-head { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; color: var(--color-text-secondary); font-size: 12px; }
.qc-results__number { font-variant-numeric: tabular-nums; }
.qc-results__finding-content { display: grid; grid-template-columns: minmax(0, 1.15fr) minmax(260px, .85fr); column-gap: 18px; align-items: start; }
.qc-results__finding-content--single { grid-template-columns: minmax(0, 1fr); }
.qc-results__issue { margin: 5px 0 0; line-height: 1.5; font-size: 14px; font-weight: 600; }
.qc-results__suggestion { margin: 5px 0 0; padding-left: 12px; border-left: 1px solid #dfe9e4; font-size: 13px; line-height: 1.5; }
.qc-results__suggestion > span { margin-right: 7px; font-size: 12px; color: var(--color-primary); }
.qc-results__excerpt { grid-column: 1 / -1; margin-top: 3px; color: var(--color-text-secondary); }
.qc-results__excerpt summary { font-size: 11px; }
.qc-results__excerpt blockquote { margin: 5px 0 0; padding: 5px 8px; border-left: 2px solid var(--border-color-base); background: #f7f9f8; font-size: 12px; line-height: 1.6; white-space: pre-wrap; }
.qc-results__empty { padding: 12px 0; color: var(--color-text-secondary); font-size: 12px; line-height: 1.6; }
.qc-results__dimension-input { width: 72px; flex-shrink: 0; }
.qc-results__dimension-summary { margin-top: 5px; }
.qc-results__toolbar-title { display: flex; align-items: center; gap: 10px; }
.qc-results__toolbar-hint { color: var(--color-text-secondary); font-size: 12px; }
.qc-results__link { padding: 2px 0; border: 0; background: transparent; color: var(--color-primary); cursor: pointer; font-size: 12px; line-height: 1.5; }
.qc-results__link:hover { text-decoration: underline; }
.qc-results__link:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.qc-results__delete { color: #b64238; }
.qc-results__locate { margin-left: auto; font-weight: 600; }
.qc-results__relation { display: inline-flex; margin-left: auto; color: var(--color-primary); font-size: 12px; font-weight: 600; }
.qc-results__finding-edit-head { align-items: center; }
.qc-results__finding-edit-head select { min-width: 68px; padding: 4px 6px; border: 1px solid var(--border-color-base); border-radius: 4px; background: #fff; color: var(--color-text-primary); font-size: 11px; }
.qc-results__finding-edit-head .qc-results__category-select { min-width: 112px; max-width: 160px; }
.qc-results__deduction-control { display: flex; align-items: center; gap: 4px; color: var(--color-text-secondary); white-space: nowrap; }
.qc-results__deduction-input { width: 58px; }
.qc-results__deduction { color: #b64238; font-weight: 600; white-space: nowrap; }
.qc-results__finding--ignored { padding-right: 6px; padding-left: 6px; background: #f7f8f7; }
.qc-results__finding--ignored .qc-results__issue { color: var(--color-text-secondary); font-weight: 400; }
.qc-results__edit-fields { display: grid; gap: 6px; margin-top: 8px; }
.qc-results__edit-fields > label { display: grid; gap: 3px; color: var(--color-text-secondary); font-size: 11px; }
.qc-results__review-footer { display: flex; align-items: flex-end; gap: 8px; padding-top: 8px; border-top: 1px solid var(--border-color-base); flex-shrink: 0; }
.qc-results__review-footer > .el-textarea { flex: 1; min-width: 0; }
.qc-results__review-actions { display: flex; gap: 6px; flex-shrink: 0; }
.qc-results__review-actions .el-button + .el-button { margin-left: 0; }
@media (max-width: 720px) {
  .qc-results__finding-content { grid-template-columns: minmax(0, 1fr); }
  .qc-results__suggestion { padding-left: 0; border-left: 0; }
  .qc-results__review-footer { align-items: stretch; flex-direction: column; }
  .qc-results__review-actions { justify-content: flex-end; }
}
</style>
