<template>
  <div class="page-surface business-debug-page">
    <section class="page-section page-section--padded business-debug-toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model.trim="keyword"
          clearable
          placeholder="搜索患者、医生、机构、问诊 ID…"
          class="search-input"
          @keyup.enter.native="loadConsultations"
        />
        <el-select v-model="status" clearable placeholder="状态" class="status-select">
          <el-option label="已生成" value="generated" />
          <el-option label="已完成" value="completed" />
          <el-option label="已放弃" value="abandoned" />
        </el-select>
        <el-button type="primary" icon="el-icon-search" @click="loadConsultations">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </section>

    <section class="business-debug-layout">
      <aside class="business-debug-list">
        <div class="panel-title">就诊记录</div>
        <el-table
          :data="consultations"
          v-loading="loading"
          highlight-current-row
          @current-change="selectConsultation"
        >
          <el-table-column label="患者" min-width="130" show-overflow-tooltip>
            <template slot-scope="{ row }">
              <div class="patient-name">{{ displayText(row.patientName) }}</div>
              <div class="subline">{{ [row.patientGender, row.patientAge].filter(Boolean).join(' / ') || '--' }}</div>
            </template>
          </el-table-column>
          <el-table-column label="医生/时间" min-width="168" show-overflow-tooltip>
            <template slot-scope="{ row }">
              <div>{{ displayText(row.doctorName) }}</div>
              <div class="subline">{{ formatDateTime(row.startedAt) }}</div>
            </template>
          </el-table-column>
        </el-table>
        <div class="page-footer">
          <AdminPagination
            compact
            :current.sync="current"
            :size.sync="size"
            :total="total"
            @change="loadConsultations"
          />
        </div>
      </aside>

      <main class="business-debug-main">
        <div v-if="!contextData" class="empty-workbench">
          <i class="el-icon-connection"></i>
          <span>选择一条语音接诊记录后开始业务节点调试。</span>
        </div>

        <template v-else>
          <section class="run-context">
            <div>
              <div class="panel-title">业务上下文</div>
              <div class="context-meta">
                <code-tag :value="contextData.run.consultationId" />
                <span>{{ displayText(contextData.run.orgName) }}</span>
                <span>{{ displayText(contextData.run.doctorName) }}</span>
                <span>{{ displayText(contextData.run.patientName) }}</span>
              </div>
            </div>
            <div class="context-actions">
              <el-button size="mini" icon="el-icon-refresh" @click="reloadContext">刷新上下文</el-button>
            </div>
          </section>

          <section class="node-strip">
            <button
              v-for="node in nodes"
              :key="node.nodeCode"
              type="button"
              class="node-step"
              :class="{ 'node-step--active': activeNode && activeNode.nodeCode === node.nodeCode, 'node-step--done': hasOutput(node.nodeCode) }"
              @click="selectNode(node)"
            >
              <span class="node-step__title">{{ node.title }}</span>
              <span class="node-step__meta">{{ node.promptCode }}</span>
            </button>
          </section>

          <section class="business-debug-workbench">
            <div class="node-editor">
              <div class="panel-title">节点入参与 Prompt</div>
              <div v-if="activeNode" class="node-summary">
                <div>
                  <span class="summary-label">节点</span>
                  <strong>{{ activeNode.title }}</strong>
                </div>
                <div>
                  <span class="summary-label">Prompt</span>
                  <strong>{{ activeNode.promptName || activeNode.promptCode }}</strong>
                </div>
                <div>
                  <span class="summary-label">来源</span>
                  <strong>{{ promptSourceLabel(activeNode.promptSource) }}</strong>
                </div>
                <div>
                  <span class="summary-label">版本</span>
                  <strong>{{ activeNode.versionNum || '--' }}</strong>
                </div>
              </div>

              <div class="source-toolbar">
                <el-button size="mini" @click="applyInputSource('speech')">原始语音文本</el-button>
                <el-button size="mini" @click="applyInputSource('last')">上游最后结果</el-button>
                <el-button size="mini" @click="applyInputSource('all')">全部上游结果</el-button>
                <el-button size="mini" @click="applyInputSource('first')">首版病历</el-button>
                <el-button size="mini" @click="applyInputSource('final')">最终病历</el-button>
              </div>

              <div class="editor-grid">
                <el-form label-position="top">
                  <el-form-item label="当前节点输入">
                    <el-input v-model="form.currentInput" type="textarea" :rows="8" placeholder="可填写当前节点的核心输入…" />
                  </el-form-item>
                  <el-form-item label="上游节点输出">
                    <el-input v-model="form.upstreamOutput" type="textarea" :rows="8" placeholder="可从上游节点结果快速载入…" />
                  </el-form-item>
                </el-form>

                <el-form label-position="top">
                  <div class="config-row-inline">
                    <el-form-item label="配置 Profile">
                      <el-select v-model="form.configProfile" class="full-control">
                        <el-option label="default" value="default" />
                        <el-option label="fast" value="fast" />
                        <el-option label="reviewer" value="reviewer" />
                      </el-select>
                    </el-form-item>
                    <el-form-item label="Temperature">
                      <el-input-number
                        v-model="form.temperature"
                        :min="0"
                        :max="2"
                        :step="0.1"
                        :precision="1"
                        class="full-control"
                      />
                    </el-form-item>
                  </div>
                  <el-form-item label="System Prompt">
                    <el-input v-model="form.systemPrompt" type="textarea" :rows="8" placeholder="从节点默认 Prompt 载入，可直接调优…" />
                  </el-form-item>
                  <el-form-item label="User Prompt">
                    <el-input
                      v-model="form.userPrompt"
                      type="textarea"
                      :rows="8"
                      :placeholder="'可使用 {{input}}、{{upstreamOutput}}、{{speechText}} 等变量…'"
                    />
                  </el-form-item>
                </el-form>
              </div>

              <div class="execute-bar">
                <el-button
                  type="primary"
                  icon="el-icon-video-play"
                  :loading="executing"
                  :disabled="!canExecute"
                  @click="executeNode"
                >
                  重放当前节点
                </el-button>
                <el-button icon="el-icon-refresh-left" @click="resetNodeForm">恢复节点默认</el-button>
              </div>
            </div>

            <aside class="node-result">
              <div class="panel-title">节点输出</div>
              <div v-if="!activeResult" class="empty-result">当前节点暂无输出。</div>
              <template v-else>
                <div class="result-head">
                  <code-tag :value="activeResult.traceId" />
                  <span>{{ activeResult.durationMs }}ms</span>
                </div>
                <pre class="json-block">{{ formatJson(activeResult.parsedJson || activeResult.content) }}</pre>
              </template>

              <div class="panel-title panel-title--history">本次调试链路</div>
              <div v-if="outputs.length === 0" class="empty-result">执行节点后会形成页面内上游链路。</div>
              <button
                v-for="item in outputs"
                :key="item.traceId"
                type="button"
                class="history-item"
                @click="loadOutputToUpstream(item)"
              >
                <span>{{ nodeTitle(item.nodeCode) }}</span>
                <small>{{ item.durationMs }}ms</small>
              </button>
            </aside>
          </section>
        </template>
      </main>
    </section>
  </div>
</template>

<script>
import http from '../api/http'
import { CodeTag } from '../components/ui'

function normalizeText(value) {
  if (value === null || value === undefined) return ''
  return String(value).trim()
}

function stringify(value) {
  if (value === null || value === undefined || value === '') return ''
  if (typeof value === 'string') return value
  try {
    return JSON.stringify(value, null, 2)
  } catch (error) {
    return String(value)
  }
}

export default {
  components: { CodeTag },
  data() {
    return {
      loading: false,
      contextLoading: false,
      executing: false,
      keyword: '',
      status: '',
      current: 1,
      size: 10,
      total: 0,
      consultations: [],
      selectedRun: null,
      contextData: null,
      nodes: [],
      activeNodeCode: '',
      outputs: [],
      form: {
        currentInput: '',
        upstreamOutput: '',
        configProfile: 'default',
        temperature: 0.2,
        systemPrompt: '',
        userPrompt: ''
      }
    }
  },
  computed: {
    activeNode() {
      return this.nodes.find(item => item.nodeCode === this.activeNodeCode) || this.nodes[0] || null
    },
    activeResult() {
      if (!this.activeNode) return null
      return this.outputs.find(item => item.nodeCode === this.activeNode.nodeCode) || null
    },
    canExecute() {
      return this.contextData && this.activeNode && normalizeText(this.form.systemPrompt) && normalizeText(this.form.userPrompt)
    }
  },
  mounted() {
    this.loadConsultations()
  },
  methods: {
    async loadConsultations() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/business-workflow-debug/consultations', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.keyword || undefined,
            status: this.status || undefined
          }
        })
        this.consultations = data.records || []
        this.total = data.total || 0
      } catch (error) {
        this.$message.error(error.message || '加载就诊记录失败')
      } finally {
        this.loading = false
      }
    },
    reset() {
      this.keyword = ''
      this.status = ''
      this.current = 1
      this.loadConsultations()
    },
    async selectConsultation(row) {
      if (!row || !row.idRun) return
      this.selectedRun = row
      this.outputs = []
      await this.loadContext(row.idRun)
    },
    async reloadContext() {
      if (!this.contextData || !this.contextData.run) return
      await this.loadContext(this.contextData.run.idRun)
    },
    async loadContext(idRun) {
      this.contextLoading = true
      try {
        this.contextData = await http.get(`/admin/api/business-workflow-debug/consultations/${idRun}/context`)
        this.nodes = this.contextData.nodes || []
        this.activeNodeCode = this.nodes.length ? this.nodes[0].nodeCode : ''
        this.resetNodeForm()
      } catch (error) {
        this.$message.error(error.message || '加载业务上下文失败')
      } finally {
        this.contextLoading = false
      }
    },
    selectNode(node) {
      this.activeNodeCode = node.nodeCode
      this.resetNodeForm()
      const last = this.outputs[this.outputs.length - 1]
      if (last) {
        this.form.upstreamOutput = stringify(last.parsedJson || last.content)
      }
    },
    resetNodeForm() {
      if (!this.activeNode) return
      this.form.configProfile = this.activeNode.defaultConfigProfile || 'default'
      this.form.temperature = this.activeNode.defaultTemperature == null ? 0.2 : this.activeNode.defaultTemperature
      this.form.systemPrompt = this.activeNode.systemPrompt || ''
      this.form.userPrompt = this.activeNode.userPrompt || ''
      this.form.currentInput = this.defaultInputForNode()
      this.form.upstreamOutput = ''
    },
    defaultInputForNode() {
      const context = this.contextData && this.contextData.context ? this.contextData.context : {}
      if (!this.activeNode || this.activeNode.nodeCode === 'voice_transcript_calibration') {
        return context.speechText || ''
      }
      const last = this.outputs[this.outputs.length - 1]
      return last ? stringify(last.parsedJson || last.content) : (context.speechText || '')
    },
    applyInputSource(source) {
      const context = this.contextData && this.contextData.context ? this.contextData.context : {}
      if (source === 'speech') {
        this.form.currentInput = context.speechText || ''
      } else if (source === 'last') {
        const last = this.outputs[this.outputs.length - 1]
        this.form.upstreamOutput = last ? stringify(last.parsedJson || last.content) : ''
      } else if (source === 'all') {
        this.form.upstreamOutput = stringify(this.outputs.map(item => ({
          nodeCode: item.nodeCode,
          title: this.nodeTitle(item.nodeCode),
          output: item.parsedJson || item.content
        })))
      } else if (source === 'first') {
        this.form.currentInput = stringify(context.firstSnapshot)
      } else if (source === 'final') {
        this.form.currentInput = stringify(context.finalSnapshot)
      }
    },
    async executeNode() {
      if (!this.canExecute) return
      this.executing = true
      try {
        const result = await http.post('/admin/api/business-workflow-debug/execute', {
          idRun: this.contextData.run.idRun,
          nodeCode: this.activeNode.nodeCode,
          systemPrompt: this.form.systemPrompt,
          userPrompt: this.form.userPrompt,
          configProfile: this.form.configProfile,
          temperature: this.form.temperature,
          inputPayload: {
            input: this.form.currentInput,
            currentInput: this.form.currentInput,
            upstreamOutput: this.form.upstreamOutput
          }
        })
        const nextOutputs = this.outputs.filter(item => item.nodeCode !== result.nodeCode)
        nextOutputs.push(result)
        this.outputs = nextOutputs
        this.$message.success('节点重放完成')
      } catch (error) {
        this.$message.error(error.message || '节点重放失败')
      } finally {
        this.executing = false
      }
    },
    loadOutputToUpstream(item) {
      this.form.upstreamOutput = stringify(item.parsedJson || item.content)
      this.$message.success('已载入上游输出')
    },
    hasOutput(nodeCode) {
      return this.outputs.some(item => item.nodeCode === nodeCode)
    },
    nodeTitle(nodeCode) {
      const node = this.nodes.find(item => item.nodeCode === nodeCode)
      return node ? node.title : nodeCode
    },
    displayText(value) {
      return normalizeText(value) || '--'
    },
    promptSourceLabel(value) {
      const map = {
        built_in: '内置默认',
        configured: '配置覆盖',
        missing: '未配置'
      }
      return map[value] || value || '--'
    },
    formatDateTime(value) {
      const text = normalizeText(value)
      return text ? text.replace('T', ' ').replace(/\.\d+$/, '') : '--'
    },
    formatJson(value) {
      if (value === null || value === undefined || value === '') return '--'
      if (typeof value === 'string') {
        try {
          return JSON.stringify(JSON.parse(value), null, 2)
        } catch (error) {
          return value
        }
      }
      return stringify(value)
    }
  }
}
</script>

<style scoped>
.business-debug-page {
  min-width: 0;
}

.business-debug-toolbar {
  display: flex;
  justify-content: space-between;
  gap: 12px;
}

.search-input {
  width: 320px;
}

.status-select {
  width: 140px;
}

.business-debug-layout {
  display: grid;
  grid-template-columns: minmax(330px, 380px) minmax(0, 1fr);
  gap: 14px;
  min-height: calc(100vh - 170px);
}

.business-debug-list,
.business-debug-main,
.node-editor,
.node-result {
  min-width: 0;
  background: #fff;
  border: 1px solid var(--border-color-base);
  border-radius: var(--radius-card);
  box-shadow: var(--shadow-card);
}

.business-debug-list {
  padding: 12px;
}

.business-debug-main {
  padding: 14px;
}

.panel-title {
  margin-bottom: 10px;
  color: var(--color-text-primary);
  font-weight: 600;
}

.panel-title--history {
  margin-top: 16px;
}

.patient-name {
  color: var(--color-text-primary);
  font-weight: 500;
}

.subline {
  margin-top: 3px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.empty-workbench {
  min-height: 420px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: var(--color-text-secondary);
}

.run-context {
  display: flex;
  justify-content: space-between;
  gap: 12px;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--border-color-light);
}

.context-meta {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  color: var(--color-text-regular);
}

.node-strip {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 8px;
  padding: 12px 0;
}

.node-step {
  min-width: 0;
  min-height: 58px;
  padding: 8px 10px;
  border: 1px solid var(--border-color-base);
  border-radius: 6px;
  background: #fff;
  text-align: left;
  cursor: pointer;
}

.node-step--active {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.node-step--done {
  box-shadow: inset 3px 0 0 var(--color-primary);
}

.node-step__title,
.node-step__meta {
  display: block;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.node-step__title {
  color: var(--color-text-primary);
  font-weight: 600;
}

.node-step__meta {
  margin-top: 5px;
  color: var(--color-text-secondary);
  font-family: var(--font-mono);
  font-size: 12px;
}

.business-debug-workbench {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 360px;
  gap: 14px;
}

.node-editor,
.node-result {
  padding: 14px;
}

.node-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  border: 1px solid var(--border-color-base);
  border-radius: 6px;
  overflow: hidden;
}

.node-summary > div {
  min-width: 0;
  padding: 8px 10px;
  border-right: 1px solid var(--border-color-light);
}

.node-summary > div:last-child {
  border-right: 0;
}

.summary-label {
  display: block;
  margin-bottom: 4px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

.node-summary strong {
  display: block;
  overflow-wrap: anywhere;
  font-weight: 500;
}

.source-toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin: 12px 0;
}

.editor-grid {
  display: grid;
  grid-template-columns: minmax(0, 0.9fr) minmax(0, 1.1fr);
  gap: 14px;
}

.config-row-inline {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}

.full-control {
  width: 100%;
}

.execute-bar {
  display: flex;
  gap: 8px;
  margin-top: 4px;
}

.result-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 8px;
  color: var(--color-text-secondary);
}

.json-block {
  min-height: 240px;
  max-height: 420px;
  margin: 0;
  padding: 10px;
  overflow: auto;
  border-radius: 6px;
  background: #F7F7F4;
  color: var(--color-text-primary);
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  font-family: var(--font-mono);
  font-size: 12px;
  line-height: 1.55;
}

.empty-result {
  padding: 18px 10px;
  border: 1px dashed var(--border-color-base);
  border-radius: 6px;
  color: var(--color-text-secondary);
  text-align: center;
}

.history-item {
  width: 100%;
  min-height: 34px;
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  padding: 7px 9px;
  border: 1px solid var(--border-color-base);
  border-radius: 6px;
  background: #fff;
  color: var(--color-text-primary);
  cursor: pointer;
}

.history-item small {
  color: var(--color-text-secondary);
}

@media (max-width: 1180px) {
  .business-debug-layout,
  .business-debug-workbench,
  .editor-grid {
    grid-template-columns: 1fr;
  }

  .node-strip {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
