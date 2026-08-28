<template>
  <div class="page-surface">
    <admin-filter-bar class="release-toolbar">
      <div class="release-toolbar__content">
        <div class="page-toolbar__filters">
          <el-select v-model="filters.channel" placeholder="发布通道" class="filter-select" @change="loadData">
            <el-option v-for="item in channelOptions" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
          <el-button @click="loadData">刷新</el-button>
        </div>
        <div class="release-toolbar__actions">
          <el-button icon="el-icon-link" @click="openDownloadPage">打开下载页</el-button>
          <el-button type="primary" icon="el-icon-upload" @click="openUpload">上传新版本</el-button>
        </div>
      </div>
    </admin-filter-bar>

    <section v-loading="loading" class="release-current-section">
      <div v-if="records.length" class="release-card-grid">
        <article v-for="row in records" :key="row.channel" class="release-channel-card">
          <header class="release-card-header">
            <div class="release-card-title">
              <status-pill tone="success" :label="channelLabel(row.channel)" />
              <strong>{{ row.version || '暂无版本' }}</strong>
            </div>
            <div class="release-card-policy">
              <span class="muted">强制更新</span>
              <el-switch
                v-model="row.forceUpdate"
                :disabled="!row.version || policySavingKey === row.channel"
                active-color="#1D9E75"
                inactive-color="#dcdfe6"
                @change="toggleForceUpdate(row, $event)"
              />
            </div>
          </header>

          <div class="release-meta-grid">
            <div class="release-meta-item">
              <span>最低可用版本</span>
              <strong>{{ row.minSupportedVersion || '--' }}</strong>
            </div>
            <div class="release-meta-item">
              <span>发布时间</span>
              <strong>{{ row.pubDate || '--' }}</strong>
            </div>
            <div class="release-meta-item release-meta-item--source">
              <span>更新源</span>
              <div class="release-source-row">
                <code-tag :value="row.latestJsonUrl || '--'" />
                <div class="release-link-actions">
                  <table-action :disabled="!row.latestJsonUrl" @click="copy(row.latestJsonUrl, '更新源')">复制</table-action>
                </div>
              </div>
            </div>
          </div>

          <div class="release-package-panel">
            <div class="release-panel-heading">
              <span>平台包</span>
              <span class="muted">{{ releasePlatforms(row).length }} 个</span>
            </div>
            <div v-if="releasePlatforms(row).length" class="release-package-list">
              <div v-for="item in releasePlatforms(row)" :key="item.target || item.fileName" class="release-package-row">
                <div class="release-package-main">
                  <code-tag :value="item.target || '--'" />
                  <div class="release-package-name">
                    <span>{{ item.fileName || '暂无上传文件' }}</span>
                    <span v-if="item.fileSize" class="muted">{{ formatFileSize(item.fileSize) }}</span>
                  </div>
                </div>
                <div class="release-package-actions">
                  <table-action :disabled="!item.downloadUrl" @click="copy(item.downloadUrl, `${item.target || '客户端'}下载链接`)">复制</table-action>
                  <table-action :disabled="!item.downloadUrl" @click="openUrl(item.downloadUrl)">打开</table-action>
                </div>
                <div v-if="item.downloadUrl" class="release-package-url">{{ item.downloadUrl }}</div>
              </div>
            </div>
            <div v-else class="release-empty-row">暂无平台包</div>
          </div>
        </article>
      </div>
      <el-empty v-else description="暂无发布版本" :image-size="80" />
    </section>

    <div class="history-header">
      <span>历史版本</span>
    </div>
    <section class="page-section page-section--table release-table-card">
      <el-table
        v-loading="historyLoading"
        :data="historyRecords"
        class="admin-table history-table"
        :row-key="historyKey"
      >
        <el-table-column label="通道" width="130">
          <template slot-scope="{ row }">
            <status-pill tone="success" :label="channelLabel(row.channel)" />
          </template>
        </el-table-column>
        <el-table-column prop="version" label="版本" width="120">
          <template slot-scope="{ row }">
            <span>{{ row.version || '--' }}</span>
            <span v-if="row.active" class="current-marker">当前</span>
          </template>
        </el-table-column>
        <el-table-column label="强制更新" width="130">
          <template slot-scope="{ row }">
            <status-pill :tone="row.forceUpdate ? 'warning' : 'muted'" :label="row.forceUpdate ? '已开启' : '未开启'" />
          </template>
        </el-table-column>
        <el-table-column prop="minSupportedVersion" label="最低可用版本" width="140">
          <template slot-scope="{ row }">{{ row.minSupportedVersion || '--' }}</template>
        </el-table-column>
        <el-table-column label="平台" min-width="220">
          <template slot-scope="{ row }">
            <div v-if="historyTargets(row).length" class="release-platform-list release-platform-list--compact">
              <code-tag v-for="target in historyTargets(row)" :key="target" :value="target" />
            </div>
            <span v-else class="muted">暂无平台</span>
          </template>
        </el-table-column>
        <el-table-column label="安装包" min-width="360">
          <template slot-scope="{ row }">
            <div v-if="historyFileNames(row).length" class="history-file-list">
              <span v-for="fileName in historyFileNames(row)" :key="fileName" class="history-file-item">{{ fileName }}</span>
            </div>
            <span v-else class="muted">暂无上传文件</span>
          </template>
        </el-table-column>
        <el-table-column prop="pubDate" label="发布时间" width="190">
          <template slot-scope="{ row }">{{ row.pubDate || '--' }}</template>
        </el-table-column>
        <el-table-column prop="updatedAt" label="最后激活" width="170">
          <template slot-scope="{ row }">{{ formatTimestamp(row.updatedAt) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template slot-scope="{ row }">
            <table-action :disabled="row.active" danger @click="rollback(row)">回滚</table-action>
          </template>
        </el-table-column>
      </el-table>
    </section>

    <el-dialog v-if="dialogVisible" title="批量上传客户端版本" :visible.sync="dialogVisible" width="760px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="发布环境" prop="channels">
            <el-select v-model="form.channels" multiple collapse-tags placeholder="请选择环境…">
              <el-option v-for="item in channelOptions" :key="item.value" :label="item.label" :value="item.value" />
            </el-select>
          </el-form-item>
          <el-form-item label="Tauri latest.json" prop="metadataFile" class="form-span-2">
            <input ref="metadataInput" type="file" accept=".json,application/json" @change="handleMetadataChange" />
            <div class="muted upload-hint">选择 Tauri 发布产物中的 latest.json，系统会自动解析版本号、平台 target 和签名。</div>
          </el-form-item>
          <el-form-item label="版本号">
            <el-input v-model.trim="form.version" placeholder="默认从 latest.json 读取…" />
          </el-form-item>
          <el-form-item label="识别到的平台">
            <div class="detected-targets">
              <code-tag v-for="item in detectedTargets" :key="item" :value="item" />
              <span v-if="!detectedTargets.length" class="muted">待解析</span>
            </div>
          </el-form-item>
          <el-form-item label="发布时间">
            <el-input v-model.trim="form.pubDate" placeholder="留空由服务端生成…" />
          </el-form-item>
          <el-form-item label="强制更新">
            <div class="switch-row">
              <el-switch
                v-model="form.forceUpdate"
                active-color="#1D9E75"
                inactive-color="#dcdfe6"
              />
              <span>低于本版本的客户端不可使用业务功能</span>
            </div>
          </el-form-item>
          <el-form-item label="更新说明" class="form-span-2">
            <el-input v-model="form.notes" type="textarea" :rows="4" placeholder="可选，展示在桌面端更新内容中…" />
          </el-form-item>
          <el-form-item label="安装包文件" prop="files" class="form-span-2">
            <input ref="fileInput" type="file" multiple @change="handleFileChange" />
            <div class="muted upload-hint">可一次选择多个平台包；服务端会按文件名匹配 latest.json 中对应 target.url 自动识别平台。</div>
            <div v-if="form.files.length" class="selected-files">
              <div v-for="(file, index) in form.files" :key="fileKey(file)" class="selected-file">
                <code-tag :value="file.name" />
                <button
                  type="button"
                  class="selected-file__remove"
                  :aria-label="`移除 ${file.name}`"
                  @click="removeSelectedFile(index)"
                >
                  <i class="el-icon-close" aria-hidden="true"></i>
                </button>
              </div>
            </div>
          </el-form-item>
        </div>
      </el-form>
      <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">批量发布</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { AdminFilterBar, CodeTag, StatusPill, TableAction } from '../components/ui'

const channelOptions = [
  { value: 'production', label: '正式内网' },
  { value: 'testing', label: '测试内网' },
  { value: 'win7-production', label: 'Win7 正式内网' },
  { value: 'win7-testing', label: 'Win7 测试内网' }
]

function createDefaultForm() {
  return {
    channels: ['production'],
    version: '',
    notes: '',
    pubDate: '',
    forceUpdate: false,
    metadataFile: null,
    files: []
  }
}

export default {
  components: {
    AdminFilterBar,
    CodeTag,
    StatusPill,
    TableAction
  },
  data() {
    return {
      channelOptions,
      detectedTargets: [],
      loading: false,
      historyLoading: false,
      policySavingKey: '',
      saving: false,
      dialogVisible: false,
      records: [],
      historyRecords: [],
      filters: {
        channel: ''
      },
      form: createDefaultForm(),
      rules: {
        channels: [{ required: true, type: 'array', min: 1, message: '请选择发布环境', trigger: 'change' }],
        metadataFile: [{ required: true, message: '请选择 latest.json', trigger: 'change' }],
        files: [{ required: true, type: 'array', min: 1, message: '请选择安装包文件', trigger: 'change' }]
      }
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      this.historyLoading = true
      try {
        const params = {
          channel: this.filters.channel || undefined
        }
        const [data, history] = await Promise.all([
          http.get('/admin/api/releases', { params }),
          http.get('/admin/api/releases/history', { params })
        ])
        this.records = Array.isArray(data) ? data : []
        this.historyRecords = Array.isArray(history) ? history : []
      } catch (error) {
        this.$message.error(error.message || '加载版本发布状态失败')
      } finally {
        this.loading = false
        this.historyLoading = false
      }
    },
    historyKey(row) {
      return `${row.channel || 'unknown'}:${row.version || 'unknown'}`
    },
    channelLabel(value) {
      const item = channelOptions.find(option => option.value === value)
      return item ? item.label : value || '--'
    },
    formatList(value) {
      return Array.isArray(value) ? value.filter(Boolean).join('、') : ''
    },
    historyTargets(row) {
      return Array.isArray(row.targets) ? row.targets.filter(Boolean) : []
    },
    historyFileNames(row) {
      return Array.isArray(row.fileNames) ? row.fileNames.filter(Boolean) : []
    },
    releasePlatforms(row) {
      if (Array.isArray(row.platforms) && row.platforms.length) {
        return row.platforms
      }
      if (row.target || row.fileName || row.downloadUrl) {
        return [{
          target: row.target,
          fileName: row.fileName,
          fileSize: row.fileSize,
          downloadUrl: row.downloadUrl
        }]
      }
      return []
    },
    downloadPlatforms(row) {
      return this.releasePlatforms(row).filter(item => item.downloadUrl)
    },
    formatTimestamp(value) {
      const timestamp = Number(value || 0)
      if (!timestamp) {
        return '--'
      }
      return new Date(timestamp).toLocaleString()
    },
    formatFileSize(value) {
      const size = Number(value || 0)
      if (size >= 1024 * 1024) {
        return `${(size / 1024 / 1024).toFixed(1)} MB`
      }
      if (size >= 1024) {
        return `${(size / 1024).toFixed(1)} KB`
      }
      return `${size} B`
    },
    openUpload() {
      this.form = createDefaultForm()
      this.detectedTargets = []
      if (this.filters.channel) {
        this.form.channels = [this.filters.channel]
      }
      this.dialogVisible = true
    },
    openDownloadPage() {
      const channel = this.filters.channel || 'production'
      this.openUrl(`/client-download?channel=${encodeURIComponent(channel)}`)
    },
    openUrl(value) {
      if (!value) {
        return
      }
      window.open(value, '_blank', 'noopener')
    },
    resetForm() {
      this.form = createDefaultForm()
      this.detectedTargets = []
      if (this.$refs.fileInput) {
        this.$refs.fileInput.value = ''
      }
      if (this.$refs.metadataInput) {
        this.$refs.metadataInput.value = ''
      }
      if (this.$refs.formRef) {
        this.$refs.formRef.resetFields()
      }
    },
    handleMetadataChange(event) {
      const files = event.target.files
      this.form.metadataFile = files && files.length > 0 ? files[0] : null
      if (this.$refs.formRef) {
        this.$refs.formRef.validateField('metadataFile')
      }
      if (this.form.metadataFile) {
        this.previewMetadata(this.form.metadataFile)
      }
    },
    previewMetadata(file) {
      const reader = new FileReader()
      reader.onload = () => {
        try {
          const metadata = JSON.parse(String(reader.result || '{}'))
          if (!this.form.version && metadata.version) {
            this.form.version = metadata.version
          }
          if (!this.form.pubDate && metadata.pub_date) {
            this.form.pubDate = metadata.pub_date
          }
          if (!this.form.notes && metadata.notes) {
            this.form.notes = metadata.notes
          }
          const targets = metadata.platforms ? Object.keys(metadata.platforms) : []
          this.detectedTargets = targets
        } catch (error) {
          this.detectedTargets = []
          this.$message.warning('latest.json 预览失败，上传时将由服务端再次校验')
        }
      }
      reader.readAsText(file)
    },
    handleFileChange(event) {
      const files = event.target.files
      const nextFiles = files && files.length > 0 ? Array.from(files) : []
      const mergedFiles = [...this.form.files]
      const existingKeys = new Set(mergedFiles.map(file => this.fileKey(file)))
      nextFiles.forEach(file => {
        const key = this.fileKey(file)
        if (!existingKeys.has(key)) {
          existingKeys.add(key)
          mergedFiles.push(file)
        }
      })
      this.form.files = mergedFiles
      event.target.value = ''
      if (this.$refs.formRef) {
        this.$refs.formRef.validateField('files')
      }
    },
    removeSelectedFile(index) {
      this.form.files.splice(index, 1)
      if (this.$refs.formRef) {
        this.$refs.formRef.validateField('files')
      }
    },
    fileKey(file) {
      if (!file) {
        return ''
      }
      return `${file.name || ''}:${file.size || 0}:${file.lastModified || 0}`
    },
    submitForm() {
      this.$refs.formRef.validate(async valid => {
        if (!valid) {
          return
        }
        this.saving = true
        try {
          const formData = new FormData()
          this.form.channels.forEach(channel => formData.append('channels', channel))
          formData.append('version', this.form.version)
          formData.append('notes', this.form.notes || '')
          formData.append('pubDate', this.form.pubDate || '')
          formData.append('forceUpdate', this.form.forceUpdate ? 'true' : 'false')
          formData.append('metadataFile', this.form.metadataFile)
          this.form.files.forEach(file => formData.append('files', file))
          await http.post('/admin/api/releases/upload/batch', formData, {
            timeout: 120000,
            headers: { 'Content-Type': 'multipart/form-data' }
          })
          this.$message.success('版本批量发布成功')
          this.dialogVisible = false
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || '版本发布失败')
        } finally {
          this.saving = false
        }
      })
    },
    async copy(value, label = '链接') {
      try {
        await navigator.clipboard.writeText(value)
        this.$message.success(`已复制${label}`)
      } catch (error) {
        this.$message.error('复制失败，请手动选择文本复制')
      }
    },
    async toggleForceUpdate(row, value) {
      if (value) {
        try {
          await this.$confirm(
            `确认开启${this.channelLabel(row.channel)}强制更新？低于 ${row.version} 的客户端将无法使用业务功能。`,
            '开启强制更新',
            { type: 'warning', confirmButtonText: '确认开启', cancelButtonText: '取消' }
          )
        } catch {
          row.forceUpdate = false
          return
        }
      }

      this.policySavingKey = row.channel
      try {
        await http.post('/admin/api/releases/policy', {
          channel: row.channel,
          forceUpdate: value
        })
        this.$message.success(value ? '已开启强制更新' : '已关闭强制更新')
        await this.loadData()
      } catch (error) {
        row.forceUpdate = !value
        this.$message.error(error.message || '切换强制更新失败')
      } finally {
        this.policySavingKey = ''
      }
    },
    async rollback(row) {
      try {
        await this.$confirm(
          `确认将${this.channelLabel(row.channel)}回滚到 ${row.version}？回滚会恢复该版本的更新包和强制更新策略。`,
          '回滚版本',
          { type: 'warning', confirmButtonText: '确认回滚', cancelButtonText: '取消' }
        )
      } catch {
        return
      }

      this.historyLoading = true
      try {
        await http.post('/admin/api/releases/rollback', {
          channel: row.channel,
          version: row.version
        })
        this.$message.success('版本回滚成功')
        await this.loadData()
      } catch (error) {
        this.$message.error(error.message || '版本回滚失败')
      } finally {
        this.historyLoading = false
      }
    }
  }
}
</script>

<style scoped>
.release-toolbar__content {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.release-table-card {
  padding: 0;
}

.release-current-section {
  min-height: 160px;
}

.release-card-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(520px, 1fr));
  gap: 14px;
}

.release-channel-card {
  border: 1px solid var(--color-border);
  border-radius: 6px;
  background: #fff;
  overflow: hidden;
}

.release-card-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border);
  background: #fbfcfc;
}

.release-card-title,
.release-card-policy,
.release-source-row,
.release-package-main,
.release-package-actions,
.release-panel-heading {
  display: flex;
  align-items: center;
}

.release-card-title {
  gap: 10px;
  min-width: 0;
}

.release-card-title strong {
  color: var(--color-text-primary);
  font-size: 18px;
  font-weight: 700;
}

.release-card-policy {
  gap: 10px;
  white-space: nowrap;
}

.release-meta-grid {
  display: grid;
  grid-template-columns: minmax(120px, 0.7fr) minmax(220px, 1.3fr);
  gap: 12px;
  padding: 14px 16px;
}

.release-meta-item {
  min-width: 0;
}

.release-meta-item span,
.release-panel-heading {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.release-meta-item strong {
  display: block;
  margin-top: 6px;
  color: var(--color-text-regular);
  font-size: 13px;
  font-weight: 600;
  word-break: break-word;
}

.release-meta-item--source {
  grid-column: 1 / -1;
}

.release-source-row {
  gap: 8px;
  margin-top: 6px;
  min-width: 0;
}

.release-source-row .code-tag,
.release-package-url {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.release-source-row .code-tag {
  display: block;
  flex: 1;
  min-width: 0;
}

.release-package-panel {
  padding: 0 16px 16px;
}

.release-panel-heading {
  justify-content: space-between;
  margin-bottom: 8px;
  font-weight: 600;
}

.release-package-list {
  display: grid;
  gap: 8px;
}

.release-package-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: 6px 12px;
  padding: 10px 12px;
  border: 1px solid #e8ecec;
  border-radius: 6px;
  background: #fcfdfd;
}

.release-package-main {
  gap: 10px;
  min-width: 0;
}

.release-package-name {
  min-width: 0;
}

.release-package-name span:first-child {
  display: block;
  overflow: hidden;
  color: var(--color-text-primary);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.release-package-actions {
  gap: 10px;
  align-self: start;
  padding-top: 2px;
}

.release-package-url {
  grid-column: 1 / -1;
  color: var(--color-text-secondary);
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, "Liberation Mono", monospace;
  font-size: 12px;
}

.release-empty-row {
  padding: 12px;
  border: 1px dashed var(--color-border);
  border-radius: 6px;
  color: var(--color-text-secondary);
  font-size: 13px;
  text-align: center;
}

.release-toolbar__actions {
  display: flex;
  gap: 8px;
  align-items: center;
  flex-wrap: wrap;
}

.release-link-actions {
  display: flex;
  gap: 10px;
  align-items: center;
}

.release-platform-list,
.selected-files,
.detected-targets {
  display: flex;
  gap: 6px;
  align-items: center;
  flex-wrap: wrap;
}

.release-platform-list--compact {
  align-items: flex-start;
}

.history-file-list {
  display: grid;
  gap: 4px;
}

.history-file-item {
  overflow: hidden;
  color: var(--color-text-regular);
  text-overflow: ellipsis;
  white-space: nowrap;
}

.selected-files {
  margin-top: 8px;
}

.selected-file {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  max-width: 100%;
}

.selected-file__remove {
  width: 22px;
  height: 22px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  background: #fff;
  color: var(--color-text-secondary);
  cursor: pointer;
  line-height: 20px;
  padding: 0;
}

.selected-file__remove:hover,
.selected-file__remove:focus-visible {
  border-color: #d95c5c;
  color: #c23b3b;
}

.selected-file__remove:focus-visible {
  outline: 2px solid rgba(29, 158, 117, 0.28);
  outline-offset: 2px;
}

.history-header {
  margin: 18px 0 10px;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text-primary);
}

.history-table {
  margin-top: 0;
}

.current-marker {
  display: inline-flex;
  margin-left: 8px;
  padding: 2px 6px;
  border-radius: 999px;
  background: rgba(29, 158, 117, 0.12);
  color: #13795b;
  font-size: 12px;
  font-weight: 600;
}

.muted {
  color: var(--color-text-secondary);
  font-size: 12px;
}

.upload-hint {
  margin-top: 8px;
}

.switch-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-height: 40px;
  color: var(--color-text-regular);
}

.is-disabled {
  color: var(--color-text-placeholder);
  cursor: not-allowed;
}

@media (max-width: 760px) {
  .release-card-grid {
    grid-template-columns: 1fr;
  }

  .release-card-header,
  .release-package-row {
    align-items: flex-start;
    grid-template-columns: 1fr;
  }

  .release-card-header {
    flex-direction: column;
  }

  .release-meta-grid {
    grid-template-columns: 1fr;
  }

  .release-package-actions {
    justify-content: flex-start;
  }
}
</style>
