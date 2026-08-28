<template>
  <div class="page-surface">
    <section class="page-section page-section--padded page-section--toolbar">
      <div class="page-toolbar__filters">
        <el-input
          v-model="keyword"
          clearable
          placeholder="输入配置编码或名称…"
          class="search-input"
          @keyup.enter.native="loadData"
        />
        <el-button type="primary" icon="el-icon-search" @click="loadData">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-button type="primary" icon="el-icon-plus" @click="openCreate">新增配置</el-button>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading">
      <el-table-column label="配置编码" min-width="140"><template slot-scope="{ row }"><code-tag :value="row.cdConfig" /></template></el-table-column>
      <el-table-column prop="naConfig" label="配置名称" min-width="160" />
      <el-table-column prop="provider" label="提供商" width="120" />
      <el-table-column prop="modelName" label="模型" min-width="140" />
      <el-table-column label="作用域" min-width="140">
        <template slot-scope="{ row }">
          {{ resolveScope(row) }}
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template slot-scope="{ row }">
          <status-pill :tone="statusTone(statusMeta(row.sdStatus).type)" :label="statusMeta(row.sdStatus).label" />
        </template>
      </el-table-column>
      <el-table-column label="接口密钥" min-width="140"><template slot-scope="{ row }"><code-tag :value="row.apiKeyMasked" /></template></el-table-column>
      <el-table-column label="语音密钥" min-width="140"><template slot-scope="{ row }"><code-tag :value="row.audioApiKeyMasked" placeholder="复用主密钥" /></template></el-table-column>
      <el-table-column label="功能开关" min-width="220">
        <template slot-scope="{ row }">
          {{ truncate(row.featuresJson) }}
        </template>
      </el-table-column>
      <el-table-column label="操作" width="160" fixed="right">
        <template slot-scope="{ row }">
          <div class="table-actions">
            <table-action @click="openEdit(row)">编辑</table-action>
            <table-action :danger="isEnabled(row)" @click="toggleStatus(row)">{{ isEnabled(row) ? '停用' : '启用' }}</table-action>
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

    <el-dialog v-if="dialogVisible" :title="dialogTitle" :visible.sync="dialogVisible" width="1080px" custom-class="config-dialog" @closed="resetForm">
      <div class="config-dialog__body">
        <el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="config-form">
          <section class="config-section">
            <h3>基础信息</h3>
            <div class="form-grid">
              <el-form-item label="配置编码">
                <el-input v-model.trim="form.cdConfig" maxlength="64" placeholder="例如 default / org001-main" />
              </el-form-item>
              <el-form-item label="配置名称" prop="naConfig">
                <el-input v-model.trim="form.naConfig" maxlength="128" placeholder="输入便于识别的配置名称…" />
              </el-form-item>
              <el-form-item label="提供商">
                <el-input v-model.trim="form.provider" maxlength="32" placeholder="例如 openai-compatible" />
              </el-form-item>
              <el-form-item label="状态" prop="sdStatus">
                <segmented-switch v-model="form.sdStatus" :options="statusOptions" />
              </el-form-item>
            </div>
          </section>

          <section class="config-section">
            <h3>主模型配置</h3>
            <div class="form-grid">
              <el-form-item label="服务地址" prop="apiBaseUrl" class="form-span-2">
                <el-input v-model.trim="form.apiBaseUrl" maxlength="500" placeholder="https://api.example.com/v1" />
              </el-form-item>
              <el-form-item label="接口密钥">
                <el-input v-model.trim="form.apiKey" show-password maxlength="1000" />
              </el-form-item>
              <el-form-item label="模型名称" prop="modelName">
                <el-input v-model.trim="form.modelName" maxlength="128" placeholder="例如 gpt-4o-mini" />
              </el-form-item>
              <el-form-item label="chatFast 模型名称">
                <el-input v-model.trim="form.fastModelName" maxlength="128" placeholder="留空则回退主模型…" />
                <p class="form-hint">仅区域化 `chatFast()` 使用；留空时服务端自动回退 `modelName`。</p>
              </el-form-item>
              <el-form-item label="思考模式">
                <el-switch v-model="form.enableThinking" />
                <p class="form-hint">控制服务端代理主模型 / `chatFast` / 审查模型时是否向上游传 `enable_thinking`。</p>
              </el-form-item>
            </div>
            <div class="test-connection-row">
              <el-button type="primary" plain :loading="testingConnection" @click="testConnection">
                {{ testingConnection ? '测试中…' : '测试连接' }}
              </el-button>
              <span v-if="testResult" :class="['test-connection-result', testResult.success ? 'success' : 'error']">
                {{ testResult.message }}
              </span>
            </div>
          </section>

          <section class="config-section">
            <h3>语音识别</h3>
            <p class="config-section__summary">桌面端优先使用实时流式识别；实时连接不可用时，录音结束后自动改用整段转写。</p>

            <div class="form-grid">
              <el-form-item label="语音提供方">
                <el-select v-model="form.speechProvider" placeholder="选择语音提供方…" @change="handleSpeechProviderChange">
                  <el-option
                    v-for="item in speechProviderOptions"
                    :key="item.value"
                    :label="item.label"
                    :value="item.value"
                  />
                </el-select>
                <p class="form-hint">{{ speechProviderHint }}</p>
              </el-form-item>
              <el-form-item label="语音服务密钥">
                <el-input v-model.trim="form.audioApiKey" show-password maxlength="1000" placeholder="留空则复用主模型接口密钥…" />
                <p class="form-hint">{{ speechApiKeyHint }}</p>
              </el-form-item>
            </div>

            <div class="config-subsection">
              <div class="config-subsection__title">
                <span>实时流式识别（优先）</span>
                <div class="config-subsection__actions">
                  <status-pill :tone="isRealtimeSpeechProvider ? 'success' : 'muted'" :label="isRealtimeSpeechProvider ? '已启用' : '未启用'" />
                  <el-button
                    size="mini"
                    plain
                    :disabled="!isRealtimeSpeechProvider || testingBatchSpeech"
                    :loading="testingRealtimeSpeech"
                    @click="testRealtimeSpeech"
                  >{{ testingRealtimeSpeech ? '测试中…' : '测试实时模型' }}</el-button>
                </div>
              </div>
              <div class="form-grid">
                <el-form-item label="实时识别地址" prop="speechRealtimeUrl" :rules="speechRealtimeUrlRules">
                  <el-input
                    v-model.trim="form.speechRealtimeUrl"
                    maxlength="500"
                    :disabled="!isRealtimeSpeechProvider"
                    :placeholder="speechRealtimeUrlPlaceholder"
                  />
                  <p class="form-hint">{{ speechRealtimeUrlHint }}</p>
                </el-form-item>
                <el-form-item label="实时识别模型" prop="speechModel" :rules="speechModelRules">
                  <el-input
                    v-model.trim="form.speechModel"
                    maxlength="128"
                    :disabled="!isRealtimeSpeechProvider"
                    :placeholder="speechModelPlaceholder"
                  />
                  <p class="form-hint">{{ speechModelHint }}</p>
                </el-form-item>
              </div>
              <div
                v-if="realtimeSpeechTestResult"
                :class="['speech-test-result', realtimeSpeechTestResult.success ? 'success' : 'error']"
                role="status"
              >
                <i :class="realtimeSpeechTestResult.success ? 'el-icon-success' : 'el-icon-error'" />
                <span>{{ realtimeSpeechTestResult.message }}</span>
              </div>
            </div>

            <div class="config-subsection">
              <div class="config-subsection__title">
                <span>整段录音转写<span v-if="isRealtimeSpeechProvider">（实时失败时兜底）</span></span>
                <el-button
                  size="mini"
                  plain
                  :disabled="testingRealtimeSpeech"
                  :loading="testingBatchSpeech"
                  @click="testBatchSpeech"
                >{{ testingBatchSpeech ? '测试中…' : '测试批量模型' }}</el-button>
              </div>
              <div class="form-grid">
                <el-form-item label="批量转写地址">
                  <el-input v-model.trim="form.audioBaseUrl" maxlength="500" placeholder="留空则复用主模型服务地址…" />
                  <p class="form-hint">{{ batchSpeechUrlHint }}</p>
                </el-form-item>
                <el-form-item label="批量转写模型" prop="audioModel" :rules="audioModelRules">
                  <el-input v-model.trim="form.audioModel" maxlength="128" :placeholder="audioModelPlaceholder" />
                  <p class="form-hint">{{ audioModelHint }}</p>
                </el-form-item>
              </div>
              <div
                v-if="batchSpeechTestResult"
                :class="['speech-test-result', batchSpeechTestResult.success ? 'success' : 'error']"
                role="status"
              >
                <i :class="batchSpeechTestResult.success ? 'el-icon-success' : 'el-icon-error'" />
                <span>{{ batchSpeechTestResult.message }}</span>
              </div>
            </div>
          </section>

          <section class="config-section">
            <h3>知识库配置</h3>
            <div class="config-subsection">
              <div class="config-subsection__title">
                <span>通用知识库</span>
                <el-switch v-model="form.knowledgeBaseEnabled" />
              </div>
              <div class="form-grid">
                <el-form-item label="知识库地址" class="form-span-2">
                  <el-input v-model.trim="form.knowledgeBaseBaseUrl" maxlength="500" :disabled="!form.knowledgeBaseEnabled" placeholder="https://knowledge.example.com" />
                </el-form-item>
              </div>
            </div>

            <div class="config-subsection">
              <div class="config-subsection__title">
                <span>人卫知识库</span>
                <el-switch v-model="form.pmphaiEnabled" />
              </div>
              <div class="form-grid">
                <el-form-item label="人卫知识库地址" class="form-span-2">
                  <el-input v-model.trim="form.pmphaiBaseUrl" maxlength="500" :disabled="!form.pmphaiEnabled" placeholder="人卫知识库服务地址" />
                </el-form-item>
                <el-form-item label="人卫应用标识">
                  <el-input v-model.trim="form.pmphaiAppKey" show-password maxlength="1000" :disabled="!form.pmphaiEnabled" />
                </el-form-item>
                <el-form-item label="人卫应用密钥">
                  <el-input v-model.trim="form.pmphaiAppSecret" show-password maxlength="1000" :disabled="!form.pmphaiEnabled" />
                </el-form-item>
              </div>
            </div>
          </section>

          <section class="config-section">
            <h3>独立审查模型</h3>
            <div class="config-subsection">
              <div class="config-subsection__title">
                <span>审查模型开关</span>
                <el-switch v-model="form.reviewerEnabled" />
              </div>
              <div class="form-grid">
                <el-form-item label="审查服务地址">
                  <el-input v-model.trim="form.reviewerBaseUrl" maxlength="500" :disabled="!form.reviewerEnabled" />
                </el-form-item>
                <el-form-item label="审查模型名称">
                  <el-input v-model.trim="form.reviewerModel" maxlength="128" :disabled="!form.reviewerEnabled" />
                </el-form-item>
                <el-form-item label="检查项目审查开关">
                  <el-switch v-model="form.reviewerCheckExaminationEnabled" :disabled="!form.reviewerEnabled" />
                  <p class="form-hint">关闭后桌面端不再触发 check_examination 独立审查，其他审查类型不受影响。</p>
                </el-form-item>
                <el-form-item label="审查接口密钥" class="form-span-2">
                  <el-input v-model.trim="form.reviewerApiKey" show-password maxlength="1000" :disabled="!form.reviewerEnabled" />
                </el-form-item>
              </div>
            </div>
          </section>

          <section class="config-section">
            <h3>作用域与功能开关</h3>
            <div class="form-grid">
              <el-form-item label="所属区域">
                <el-select v-model="form.idRegion" clearable filterable placeholder="全局">
                  <el-option v-for="item in regionOptions" :key="item.idRegion" :label="item.naRegion" :value="item.idRegion" />
                </el-select>
              </el-form-item>
              <el-form-item label="所属机构">
                <el-select v-model="form.idOrg" clearable filterable placeholder="区域/全局" @change="syncRegionByOrg">
                  <el-option v-for="item in orgOptions" :key="item.idOrg" :label="item.naOrg" :value="item.idOrg" />
                </el-select>
              </el-form-item>
              <el-form-item label="功能开关配置" prop="featuresJson" class="form-span-2">
                <el-input
                  v-model="form.featuresJson"
                  type="textarea"
                  :rows="6"
                  placeholder='例如：{"regionalMode":true,"aiProxyEnabled":true,"auditEnabled":true}'
                />
              </el-form-item>
            </div>
          </section>
        </el-form>
      </div>
      <span slot="footer">
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="saving" @click="submitForm">保存</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { fetchOrgs, fetchRegions } from '../api/reference'
import {
  buildLabelMap,
  configStatusOptions,
  findStatusMeta,
  flagToBoolean,
  resolveScopeLabel,
  statusTone,
  truncate
} from '../utils/admin'
import { CodeTag, SegmentedSwitch, StatusPill, TableAction } from '../components/ui'

const DEFAULT_AUDIO_MODEL = 'whisper-1'
const DEFAULT_DASHSCOPE_AUDIO_MODEL = 'qwen3-asr-flash'
const DEFAULT_DASHSCOPE_REALTIME_MODEL = 'qwen-audio-3.0-asr-flash-streaming'
const LEGACY_DASHSCOPE_REALTIME_MODEL = 'paraformer-realtime-v2'
const DEFAULT_FUNASR_REALTIME_MODEL = 'funasr-2pass'
const DEFAULT_DASHSCOPE_BASE_URL = 'https://dashscope.aliyuncs.com/compatible-mode/v1'
const DEFAULT_SPEECH_PROVIDER = 'openai-compatible'
const ALIYUN_SPEECH_PROVIDER = 'aliyun-dashscope'
const FUNASR_SPEECH_PROVIDER = 'funasr-websocket'
const SPEECH_PROVIDER_OPTIONS = [
  {
    value: DEFAULT_SPEECH_PROVIDER,
    label: 'OpenAI 兼容接口',
    description: '仅在录音结束后进行整段转写，不启用实时 WebSocket。'
  },
  {
    value: ALIYUN_SPEECH_PROVIDER,
    label: '阿里云 DashScope',
    description: '实时识别走 DashScope WebSocket，整段转写走兼容模式接口。'
  },
  {
    value: FUNASR_SPEECH_PROVIDER,
    label: 'FunASR WebSocket',
    description: '实时识别走自建 FunASR 2pass WebSocket，整段兜底需另外配置转写接口。'
  }
]

function normalizeSpeechProvider(value) {
  const normalized = String(value || '').trim().toLowerCase()
  if (normalized === 'aliyun' || normalized === 'dashscope' || normalized === ALIYUN_SPEECH_PROVIDER) {
    return ALIYUN_SPEECH_PROVIDER
  }
  if (normalized === 'funasr' || normalized === FUNASR_SPEECH_PROVIDER) {
    return FUNASR_SPEECH_PROVIDER
  }
  return DEFAULT_SPEECH_PROVIDER
}

function resolveSpeechModel(provider, speechModel, audioModel) {
  const normalizedProvider = normalizeSpeechProvider(provider)
  const normalizedModel = String(speechModel || '').trim()
  if (normalizedModel) return normalizedModel
  if (normalizedProvider === ALIYUN_SPEECH_PROVIDER) {
    return DEFAULT_DASHSCOPE_REALTIME_MODEL
  }
  if (normalizedProvider === FUNASR_SPEECH_PROVIDER) {
    return DEFAULT_FUNASR_REALTIME_MODEL
  }
  return audioModel || DEFAULT_AUDIO_MODEL
}

function isUnsupportedDashScopeRealtimeModel(model) {
  return String(model || '').trim().toLowerCase().indexOf('qwen3-asr-flash-realtime') === 0
}

function isDashScopeRealtimeOnlyModel(model) {
  return String(model || '').trim().toLowerCase().indexOf('qwen-audio-3.0-asr-flash-streaming') === 0
}

function createDefaultForm() {
  return {
    idConfig: '',
    cdConfig: '',
    naConfig: '',
    provider: '',
    apiBaseUrl: '',
    apiKey: '',
    modelName: '',
    fastModelName: '',
    enableThinking: false,
    audioBaseUrl: '',
    audioApiKey: '',
    audioModel: DEFAULT_AUDIO_MODEL,
    speechProvider: DEFAULT_SPEECH_PROVIDER,
    speechRealtimeUrl: '',
    speechModel: DEFAULT_AUDIO_MODEL,
    knowledgeBaseEnabled: false,
    knowledgeBaseBaseUrl: '',
    pmphaiEnabled: false,
    pmphaiBaseUrl: '',
    pmphaiAppKey: '',
    pmphaiAppSecret: '',
    reviewerEnabled: false,
    reviewerBaseUrl: '',
    reviewerApiKey: '',
    reviewerModel: '',
    reviewerCheckExaminationEnabled: true,
    featuresJson: '',
    idOrg: '',
    idRegion: '',
    sdStatus: '1'
  }
}

export default {
  components: {
    CodeTag,
    SegmentedSwitch,
    StatusPill,
    TableAction
  },
  data() {
    return {
      loading: false,
      saving: false,
      testingConnection: false,
      testingRealtimeSpeech: false,
      testingBatchSpeech: false,
      dialogVisible: false,
      dialogMode: 'create',
      keyword: '',
      current: 1,
      size: 10,
      total: 0,
      records: [],
      regionOptions: [],
      orgOptions: [],
      regionMap: {},
      orgMap: {},
      testResult: null,
      realtimeSpeechTestResult: null,
      batchSpeechTestResult: null,
      speechProviderOptions: SPEECH_PROVIDER_OPTIONS,
      statusOptions: configStatusOptions,
      form: createDefaultForm(),
      rules: {
        naConfig: [{ required: true, message: '请输入配置名称', trigger: 'blur' }],
        apiBaseUrl: [{ required: true, message: '请输入 AI 服务地址', trigger: 'blur' }],
        modelName: [{ required: true, message: '请输入模型名称', trigger: 'blur' }]
      }
    }
  },
  computed: {
    dialogTitle() {
      return this.dialogMode === 'create' ? '新增配置' : '编辑配置'
    },
    speechProviderHint() {
      const provider = normalizeSpeechProvider(this.form.speechProvider)
      const option = SPEECH_PROVIDER_OPTIONS.find(item => item.value === provider)
      return option ? option.description : ''
    },
    speechApiKeyHint() {
      const provider = normalizeSpeechProvider(this.form.speechProvider)
      if (provider === ALIYUN_SPEECH_PROVIDER) {
        return '用于 DashScope 实时识别和整段转写；留空则复用主模型密钥。'
      }
      if (provider === FUNASR_SPEECH_PROVIDER) {
        return '仅用于整段兜底转写；FunASR 实时连接不携带该密钥。'
      }
      return '用于整段录音转写；留空则复用主模型密钥。'
    },
    isFunAsrProvider() {
      return normalizeSpeechProvider(this.form.speechProvider) === FUNASR_SPEECH_PROVIDER
    },
    isRealtimeSpeechProvider() {
      return normalizeSpeechProvider(this.form.speechProvider) !== DEFAULT_SPEECH_PROVIDER
    },
    speechRealtimeUrlPlaceholder() {
      if (this.isFunAsrProvider) {
        return '例如 ws://funasr.internal:10095'
      }
      return this.isRealtimeSpeechProvider ? '留空则使用 DashScope 官方地址…' : '当前提供方不使用实时地址'
    },
    speechRealtimeUrlHint() {
      if (this.isFunAsrProvider) {
        return '后台实际连接的 FunASR WebSocket 地址，必填。'
      }
      if (this.isRealtimeSpeechProvider) {
        return '中国内地留空即可，默认 wss://dashscope.aliyuncs.com/api-ws/v1/inference；国际站需填写 intl 地址。'
      }
      return '选择 DashScope 或 FunASR 后可配置实时识别。'
    },
    speechRealtimeUrlRules() {
      const rules = []
      if (this.isFunAsrProvider) {
        rules.push({ required: true, message: '请输入 FunASR 实时识别地址', trigger: 'blur' })
      }
      rules.push({
        pattern: /^wss?:\/\/[^\s/]+(?::\d+)?(?:\/.*)?$/i,
        message: '请输入有效的 ws:// 或 wss:// 地址',
        trigger: 'blur'
      })
      return rules
    },
    speechModelRules() {
      return [{
        validator: (rule, value, callback) => {
          const provider = normalizeSpeechProvider(this.form.speechProvider)
          if (provider === ALIYUN_SPEECH_PROVIDER && isUnsupportedDashScopeRealtimeModel(value)) {
            callback(new Error('qwen3-asr-flash-realtime 协议暂不支持，请填写 DashScope run-task 协议模型'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }]
    },
    speechModelPlaceholder() {
      const provider = normalizeSpeechProvider(this.form.speechProvider)
      if (provider === ALIYUN_SPEECH_PROVIDER) return DEFAULT_DASHSCOPE_REALTIME_MODEL
      if (provider === FUNASR_SPEECH_PROVIDER) return DEFAULT_FUNASR_REALTIME_MODEL
      return this.form.audioModel || DEFAULT_AUDIO_MODEL
    },
    speechModelHint() {
      const provider = normalizeSpeechProvider(this.form.speechProvider)
      if (provider === ALIYUN_SPEECH_PROVIDER) {
        return '推荐 qwen-audio-3.0-asr-flash-streaming，走 DashScope run-task WebSocket；不要填到批量转写模型。'
      }
      if (provider === FUNASR_SPEECH_PROVIDER) {
        return '用于配置识别和日志展示；实际模型由 FunASR 服务部署决定。'
      }
      return '当前提供方不启用实时识别。'
    },
    audioModelPlaceholder() {
      return normalizeSpeechProvider(this.form.speechProvider) === ALIYUN_SPEECH_PROVIDER
        ? DEFAULT_DASHSCOPE_AUDIO_MODEL
        : DEFAULT_AUDIO_MODEL
    },
    batchSpeechUrlHint() {
      const provider = normalizeSpeechProvider(this.form.speechProvider)
      if (provider === ALIYUN_SPEECH_PROVIDER) {
        return 'DashScope 兼容模式 Base URL，后台调用 /chat/completions。'
      }
      return 'OpenAI 兼容 Base URL，后台调用 /audio/transcriptions。'
    },
    audioModelHint() {
      const provider = normalizeSpeechProvider(this.form.speechProvider)
      if (provider === ALIYUN_SPEECH_PROVIDER) {
        return '用于整段录音转写和实时失败兜底；默认 qwen3-asr-flash。不能填写带 -streaming 的实时模型。'
      }
      return this.isRealtimeSpeechProvider
        ? '用于整段录音转写和实时失败兜底；默认 whisper-1。'
        : '用于整段录音转写；默认 whisper-1。'
    },
    audioModelRules() {
      return [{
        validator: (rule, value, callback) => {
          const provider = normalizeSpeechProvider(this.form.speechProvider)
          if (provider === ALIYUN_SPEECH_PROVIDER && isDashScopeRealtimeOnlyModel(value)) {
            callback(new Error('该模型仅支持实时 WebSocket，请填写到“实时识别模型”'))
            return
          }
          callback()
        },
        trigger: 'blur'
      }]
    },
    speechTestFingerprint() {
      return [
        this.form.speechProvider,
        this.form.speechRealtimeUrl,
        this.form.speechModel,
        this.form.audioBaseUrl,
        this.form.audioModel,
        this.form.audioApiKey,
        this.form.apiKey
      ].join('|')
    }
  },
  watch: {
    speechTestFingerprint() {
      this.realtimeSpeechTestResult = null
      this.batchSpeechTestResult = null
    }
  },
  async mounted() {
    await this.loadReferences()
    this.loadData()
  },
  methods: {
    truncate,
    statusTone,
    statusMeta(value) {
      return findStatusMeta(configStatusOptions, value)
    },
    isEnabled(row) {
      return row && row.sdStatus === '1'
    },
    resolveScope(row) {
      return resolveScopeLabel(row, this.orgMap, this.regionMap)
    },
    async loadReferences() {
      const [regions, orgs] = await Promise.all([fetchRegions(), fetchOrgs()])
      this.regionOptions = regions
      this.orgOptions = orgs
      this.regionMap = buildLabelMap(regions, 'idRegion', 'naRegion')
      this.orgMap = buildLabelMap(orgs, 'idOrg', 'naOrg')
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/configs', {
          params: {
            current: this.current,
            size: this.size,
            keyword: this.keyword || undefined
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
    reset() {
      this.keyword = ''
      this.current = 1
      this.loadData()
    },
    openCreate() {
      this.dialogMode = 'create'
      this.form = createDefaultForm()
      this.testResult = null
      this.realtimeSpeechTestResult = null
      this.batchSpeechTestResult = null
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogMode = 'edit'
      const audioModel = row.audioModel || DEFAULT_AUDIO_MODEL
      const speechProvider = normalizeSpeechProvider(row.speechProvider)
      this.form = {
        idConfig: row.idConfig,
        cdConfig: row.cdConfig || '',
        naConfig: row.naConfig || '',
        provider: row.provider || '',
        apiBaseUrl: row.apiBaseUrl || '',
        apiKey: '',
        modelName: row.modelName || '',
        fastModelName: row.fastModelName || '',
        enableThinking: Boolean(row.enableThinking),
        audioBaseUrl: row.audioBaseUrl || '',
        audioApiKey: '',
        audioModel,
        speechProvider,
        speechRealtimeUrl: row.speechRealtimeUrl || '',
        speechModel: resolveSpeechModel(speechProvider, row.speechModel || '', audioModel),
        knowledgeBaseEnabled: flagToBoolean(row.knowledgeBaseEnabled),
        knowledgeBaseBaseUrl: row.knowledgeBaseBaseUrl || '',
        pmphaiEnabled: flagToBoolean(row.pmphaiEnabled),
        pmphaiBaseUrl: row.pmphaiBaseUrl || '',
        pmphaiAppKey: '',
        pmphaiAppSecret: '',
        reviewerEnabled: flagToBoolean(row.reviewerEnabled),
        reviewerBaseUrl: row.reviewerBaseUrl || '',
        reviewerApiKey: '',
        reviewerModel: row.reviewerModel || '',
        reviewerCheckExaminationEnabled: row.reviewerCheckExaminationEnabled !== false,
        featuresJson: row.featuresJson || '',
        idOrg: row.idOrg || '',
        idRegion: row.idRegion || '',
        sdStatus: row.sdStatus || '1'
      }
      this.testResult = null
      this.realtimeSpeechTestResult = null
      this.batchSpeechTestResult = null
      this.dialogVisible = true
    },
    syncRegionByOrg(idOrg) {
      const org = this.orgOptions.find(item => item.idOrg === idOrg)
      if (org) {
        this.form.idRegion = org.idRegion || ''
      }
    },
    handleSpeechProviderChange(provider) {
      const normalized = normalizeSpeechProvider(provider)
      this.form.speechProvider = normalized
      const defaultModels = [DEFAULT_AUDIO_MODEL, DEFAULT_DASHSCOPE_AUDIO_MODEL, DEFAULT_DASHSCOPE_REALTIME_MODEL, LEGACY_DASHSCOPE_REALTIME_MODEL, DEFAULT_FUNASR_REALTIME_MODEL]
      if (normalized === ALIYUN_SPEECH_PROVIDER) {
        if (!this.form.audioBaseUrl) {
          this.form.audioBaseUrl = DEFAULT_DASHSCOPE_BASE_URL
        }
        if (!this.form.audioModel || defaultModels.indexOf(this.form.audioModel) > -1) {
          this.form.audioModel = DEFAULT_DASHSCOPE_AUDIO_MODEL
        }
      } else {
        if (normalized === FUNASR_SPEECH_PROVIDER && this.form.audioBaseUrl === DEFAULT_DASHSCOPE_BASE_URL) {
          this.form.audioBaseUrl = ''
        }
        if (!this.form.audioModel || defaultModels.indexOf(this.form.audioModel) > -1) {
          this.form.audioModel = DEFAULT_AUDIO_MODEL
        }
      }
      this.form.speechRealtimeUrl = ''
      this.form.speechModel = resolveSpeechModel(normalized, '', this.form.audioModel)
      this.realtimeSpeechTestResult = null
      this.batchSpeechTestResult = null
      this.$nextTick(() => {
        if (this.$refs.formRef) {
          this.$refs.formRef.clearValidate(['speechRealtimeUrl', 'speechModel'])
        }
      })
    },
    resetForm() {
      this.form = createDefaultForm()
      this.testResult = null
      this.realtimeSpeechTestResult = null
      this.batchSpeechTestResult = null
      if (this.$refs.formRef) {
        this.$refs.formRef.resetFields()
      }
    },
    async testConnection() {
      this.testingConnection = true
      this.testResult = null
      try {
        const payload = {
          idConfig: this.form.idConfig || undefined,
          apiBaseUrl: this.form.apiBaseUrl,
          apiKey: this.form.apiKey,
          modelName: this.form.modelName
        }
        const result = await http.post('/admin/api/configs/test', payload)
        this.testResult = {
          success: true,
          message: result.message || `已连通 ${result.modelName || this.form.modelName}`
        }
      } catch (error) {
        this.testResult = {
          success: false,
          message: error.message || '测试失败'
        }
      } finally {
        this.testingConnection = false
      }
    },
    buildSpeechTestPayload() {
      const speechProvider = normalizeSpeechProvider(this.form.speechProvider)
      const audioModel = this.form.audioModel || (speechProvider === ALIYUN_SPEECH_PROVIDER
        ? DEFAULT_DASHSCOPE_AUDIO_MODEL
        : DEFAULT_AUDIO_MODEL)
      return {
        idConfig: this.form.idConfig || undefined,
        apiBaseUrl: this.form.apiBaseUrl,
        apiKey: this.form.apiKey,
        audioBaseUrl: this.form.audioBaseUrl,
        audioApiKey: this.form.audioApiKey,
        audioModel,
        speechProvider,
        speechRealtimeUrl: this.form.speechRealtimeUrl,
        speechModel: resolveSpeechModel(speechProvider, this.form.speechModel, audioModel)
      }
    },
    async testRealtimeSpeech() {
      this.testingRealtimeSpeech = true
      this.realtimeSpeechTestResult = null
      const fingerprint = this.speechTestFingerprint
      const payload = this.buildSpeechTestPayload()
      try {
        const result = await http.post('/admin/api/configs/test/speech/realtime', payload)
        if (fingerprint !== this.speechTestFingerprint) return
        this.realtimeSpeechTestResult = {
          success: true,
          message: `${result.message || '实时识别模型可用'}：${result.modelName || payload.speechModel}`
        }
      } catch (error) {
        if (fingerprint !== this.speechTestFingerprint) return
        this.realtimeSpeechTestResult = {
          success: false,
          message: error.message || '实时识别模型不可用'
        }
      } finally {
        this.testingRealtimeSpeech = false
      }
    },
    async testBatchSpeech() {
      this.testingBatchSpeech = true
      this.batchSpeechTestResult = null
      const fingerprint = this.speechTestFingerprint
      const payload = this.buildSpeechTestPayload()
      try {
        const result = await http.post('/admin/api/configs/test/speech/batch', payload)
        if (fingerprint !== this.speechTestFingerprint) return
        this.batchSpeechTestResult = {
          success: true,
          message: `${result.message || '批量转写模型可用'}：${result.modelName || payload.audioModel}`
        }
      } catch (error) {
        if (fingerprint !== this.speechTestFingerprint) return
        this.batchSpeechTestResult = {
          success: false,
          message: error.message || '批量转写模型不可用'
        }
      } finally {
        this.testingBatchSpeech = false
      }
    },
    submitForm() {
      this.$refs.formRef.validate(async valid => {
        if (!valid) {
          return
        }
        this.saving = true
        try {
          const audioModel = this.form.audioModel || DEFAULT_AUDIO_MODEL
          const speechProvider = normalizeSpeechProvider(this.form.speechProvider)
          const payload = {
            cdConfig: this.form.cdConfig,
            naConfig: this.form.naConfig,
            provider: this.form.provider,
            apiBaseUrl: this.form.apiBaseUrl,
            apiKey: this.form.apiKey,
            modelName: this.form.modelName,
            fastModelName: this.form.fastModelName,
            enableThinking: this.form.enableThinking,
            audioBaseUrl: this.form.audioBaseUrl,
            audioApiKey: this.form.audioApiKey,
            audioModel,
            speechProvider,
            speechRealtimeUrl: this.form.speechRealtimeUrl,
            speechModel: resolveSpeechModel(speechProvider, this.form.speechModel, audioModel),
            knowledgeBaseEnabled: this.form.knowledgeBaseEnabled,
            knowledgeBaseBaseUrl: this.form.knowledgeBaseBaseUrl,
            pmphaiEnabled: this.form.pmphaiEnabled,
            pmphaiBaseUrl: this.form.pmphaiBaseUrl,
            pmphaiAppKey: this.form.pmphaiAppKey,
            pmphaiAppSecret: this.form.pmphaiAppSecret,
            reviewerEnabled: this.form.reviewerEnabled,
            reviewerBaseUrl: this.form.reviewerBaseUrl,
            reviewerApiKey: this.form.reviewerApiKey,
            reviewerModel: this.form.reviewerModel,
            reviewerCheckExaminationEnabled: this.form.reviewerCheckExaminationEnabled,
            featuresJson: this.form.featuresJson,
            idOrg: this.form.idOrg || null,
            idRegion: this.form.idRegion || null,
            sdStatus: this.form.sdStatus
          }
          if (this.dialogMode === 'create') {
            await http.post('/admin/api/configs', payload)
          } else {
            await http.put(`/admin/api/configs/${this.form.idConfig}`, payload)
          }
          this.$message.success('保存成功')
          this.dialogVisible = false
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || '保存失败')
        } finally {
          this.saving = false
        }
      })
    },
    toggleStatus(row) {
      const enable = !this.isEnabled(row)
      const actionText = enable ? '启用' : '停用'
      this.$confirm(`确认${actionText}配置「${row.naConfig}」吗？`, '提示', {
        type: 'warning'
      }).then(async () => {
        try {
          await this.updateConfigStatus(row, enable ? '1' : '0')
          this.$message.success(`${actionText}成功`)
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || `${actionText}失败`)
        }
      }).catch(() => {})
    },
    updateConfigStatus(row, sdStatus) {
      const speechProvider = normalizeSpeechProvider(row.speechProvider)
      const audioModel = row.audioModel || DEFAULT_AUDIO_MODEL
      return http.put(`/admin/api/configs/${row.idConfig}`, {
        cdConfig: row.cdConfig || '',
        naConfig: row.naConfig || '',
        provider: row.provider || '',
        apiBaseUrl: row.apiBaseUrl || '',
        apiKey: '',
        modelName: row.modelName || '',
        fastModelName: row.fastModelName || '',
        enableThinking: Boolean(row.enableThinking),
        audioBaseUrl: row.audioBaseUrl || '',
        audioApiKey: '',
        audioModel,
        speechProvider,
        speechRealtimeUrl: row.speechRealtimeUrl || '',
        speechModel: resolveSpeechModel(speechProvider, row.speechModel || '', audioModel),
        knowledgeBaseEnabled: flagToBoolean(row.knowledgeBaseEnabled),
        knowledgeBaseBaseUrl: row.knowledgeBaseBaseUrl || '',
        pmphaiEnabled: flagToBoolean(row.pmphaiEnabled),
        pmphaiBaseUrl: row.pmphaiBaseUrl || '',
        pmphaiAppKey: '',
        pmphaiAppSecret: '',
        reviewerEnabled: flagToBoolean(row.reviewerEnabled),
        reviewerBaseUrl: row.reviewerBaseUrl || '',
        reviewerApiKey: '',
        reviewerModel: row.reviewerModel || '',
        reviewerCheckExaminationEnabled: row.reviewerCheckExaminationEnabled !== false,
        featuresJson: row.featuresJson || '',
        idOrg: row.idOrg || null,
        idRegion: row.idRegion || null,
        sdStatus
      })
    }
  }
}
</script>

<style scoped>
.config-dialog__body {
  max-height: 70vh;
  overflow-y: auto;
  padding-right: 6px;
}

.config-section {
  margin-bottom: 22px;
}

.config-section h3 {
  margin: 0 0 12px;
  font-size: 13px;
  font-weight: 500;
  color: #2C2C2A;
}

.config-section__summary {
  margin: -4px 0 16px;
  color: #5F636B;
  font-size: 12px;
  line-height: 1.6;
}

.config-subsection {
  margin-bottom: 18px;
}

.config-subsection__title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 12px;
  font-size: 13px;
  font-weight: 500;
  color: #2C2C2A;
}

.config-subsection__actions {
  display: flex;
  align-items: center;
  gap: 10px;
}

.speech-test-result {
  display: flex;
  align-items: flex-start;
  gap: 7px;
  margin: -6px 0 16px;
  font-size: 12px;
  line-height: 1.55;
}

.speech-test-result i {
  margin-top: 2px;
}

.speech-test-result.success {
  color: #0F6E56;
}

.speech-test-result.error {
  color: #A32D2D;
}

.test-connection-row {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 2px;
  margin-bottom: 12px;
}

.test-connection-result {
  font-size: 13px;
  line-height: 1.6;
}

.test-connection-result.success {
  color: #0F6E56;
}

.test-connection-result.error {
  color: #A32D2D;
}

.form-hint {
  margin: 6px 0 0;
  color: #7A7D85;
  font-size: 12px;
  line-height: 1.5;
}

.config-form :deep(.el-select) {
  width: 100%;
}

.config-form :deep(.el-form-item) {
  margin-bottom: 18px;
}
</style>
