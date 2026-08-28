<template>
  <div class="page-surface">
    <section class="page-section page-section--padded">
      <div class="page-toolbar__filters sec-filter-grid">
        <el-select
          v-model="filters.rejectionType"
          clearable
          placeholder="拦截类型"
          class="filter-select"
        >
          <el-option
            v-for="item in rejectionTypeOptions"
            :key="item.value"
            :label="item.label"
            :value="item.value"
          />
        </el-select>
        <el-input
          v-model.trim="filters.requestPath"
          clearable
          placeholder="请求路径"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-input
          v-model.trim="filters.clientIp"
          clearable
          placeholder="客户端 IP"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-input
          v-model.trim="filters.idDevice"
          clearable
          placeholder="设备 ID"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-input
          v-model.trim="filters.rejectReason"
          clearable
          placeholder="拦截原因"
          class="filter-input"
          @keyup.enter.native="handleSearch"
        />
        <el-date-picker
          v-model="filters.dateRange"
          type="daterange"
          clearable
          unlink-panels
          range-separator="至"
          start-placeholder="开始日期"
          end-placeholder="结束日期"
          value-format="yyyy-MM-dd"
          class="filter-date"
        />
        <el-button type="primary" icon="el-icon-search" @click="handleSearch">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading">
        <el-table-column label="拦截类型" width="160">
          <template slot-scope="{ row }">
            <el-tag size="mini" :type="rejectionTypeMeta(row.rejectionType).type">
              {{ rejectionTypeMeta(row.rejectionType).label }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="请求" min-width="180" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <span class="sec-method">{{ row.requestMethod }}</span>
            <span class="sec-path">{{ row.requestPath }}</span>
          </template>
        </el-table-column>
        <el-table-column label="客户端 IP" width="140" show-overflow-tooltip>
          <template slot-scope="{ row }">
            {{ row.clientIp || '--' }}
          </template>
        </el-table-column>
        <el-table-column label="设备" min-width="130" show-overflow-tooltip>
          <template slot-scope="{ row }">
            {{ row.cdDevice || row.idDevice || '--' }}
          </template>
        </el-table-column>
        <el-table-column label="拦截原因" min-width="160" show-overflow-tooltip>
          <template slot-scope="{ row }">
            {{ row.rejectReason || '--' }}
          </template>
        </el-table-column>
        <el-table-column label="签名" width="70" align="center">
          <template slot-scope="{ row }">
            <status-pill v-if="row.hasSignature === '1'" tone="warning" label="有" />
            <status-pill v-else tone="muted" label="无" />
          </template>
        </el-table-column>
        <el-table-column label="时间" width="168">
          <template slot-scope="{ row }">
            {{ formatDateTime(row.insertTime) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="96" fixed="right">
          <template slot-scope="{ row }">
            <table-action @click="openDetail(row)">详情</table-action>
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

    <el-dialog v-if="detailVisible" title="拦截详情" :visible.sync="detailVisible" width="720px">
      <div v-if="detailRecord" class="detail-grid">
        <div class="detail-card">
          <div class="detail-card__label">拦截类型</div>
          <div class="detail-card__value">
            <el-tag size="mini" :type="rejectionTypeMeta(detailRecord.rejectionType).type">
              {{ rejectionTypeMeta(detailRecord.rejectionType).label }}
            </el-tag>
          </div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">请求方法</div>
          <div class="detail-card__value"><code-tag :value="detailRecord.requestMethod" /></div>
        </div>
        <div class="detail-card detail-card--full">
          <div class="detail-card__label">请求路径</div>
          <div class="detail-card__value sec-path"><code-tag :value="detailRecord.requestPath" /></div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">客户端 IP</div>
          <div class="detail-card__value">{{ detailRecord.clientIp || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">设备编码</div>
          <div class="detail-card__value">{{ detailRecord.cdDevice || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">设备 ID</div>
          <div class="detail-card__value">{{ detailRecord.idDevice || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">机构 ID</div>
          <div class="detail-card__value">{{ detailRecord.idOrg || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">请求追踪 ID</div>
          <div class="detail-card__value">{{ detailRecord.requestId || '--' }}</div>
        </div>
        <div class="detail-card detail-card--full">
          <div class="detail-card__label">拦截原因</div>
          <div class="detail-card__value sec-reason">{{ detailRecord.rejectReason || '--' }}</div>
        </div>
        <div class="detail-card detail-card--full">
          <div class="detail-card__label">拦截详情</div>
          <div class="detail-card__value sec-detail">{{ detailRecord.rejectDetail || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">是否携带签名</div>
          <div class="detail-card__value">
            <status-pill v-if="detailRecord.hasSignature === '1'" tone="warning" label="是" />
            <status-pill v-else tone="muted" label="否" />
          </div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">X-Timestamp</div>
          <div class="detail-card__value">{{ detailRecord.timestampHeader || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">X-Nonce</div>
          <div class="detail-card__value">{{ detailRecord.nonceHeader || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">客户端版本</div>
          <div class="detail-card__value">{{ detailRecord.clientVersion || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">更新通道</div>
          <div class="detail-card__value">{{ detailRecord.updateChannel || '--' }}</div>
        </div>
        <div class="detail-card">
          <div class="detail-card__label">拦截时间</div>
          <div class="detail-card__value">{{ formatDateTime(detailRecord.insertTime) }}</div>
        </div>
      </div>
      <span slot="footer">
        <el-button @click="detailVisible = false">关闭</el-button>
      </span>
    </el-dialog>
  </div>
</template>

<script>
import http from '../api/http'
import { CodeTag, StatusPill, TableAction } from '../components/ui'

const REJECTION_TYPE_MAP = {
  AUTH_MISSING_TOKEN: { label: '缺少令牌', type: 'danger', group: 'auth' },
  AUTH_INVALID_TOKEN: { label: '令牌无效', type: 'danger', group: 'auth' },
  SIG_MISSING: { label: '缺少签名', type: 'warning', group: 'sig' },
  SIG_INVALID: { label: '签名无效', type: 'danger', group: 'sig' },
  SIG_NO_PUBLIC_KEY: { label: '未注册公钥', type: 'warning', group: 'sig' },
  VERSION_OUTDATED: { label: '版本过低', type: 'info', group: 'version' },
  WS_AUTH_MISSING_TOKEN: { label: 'WS缺少令牌', type: 'danger', group: 'ws' },
  WS_AUTH_INVALID_TOKEN: { label: 'WS令牌无效', type: 'danger', group: 'ws' },
  WS_SIG_MISSING: { label: 'WS缺少签名', type: 'warning', group: 'ws' },
  WS_SIG_INVALID: { label: 'WS签名无效', type: 'danger', group: 'ws' },
  WS_SIG_NO_PUBLIC_KEY: { label: 'WS未注册公钥', type: 'warning', group: 'ws' }
}

function createDefaultFilters() {
  return {
    rejectionType: '',
    requestPath: '',
    clientIp: '',
    idDevice: '',
    rejectReason: '',
    dateRange: []
  }
}

export default {
  components: {
    CodeTag,
    StatusPill,
    TableAction
  },
  data() {
    return {
      loading: false,
      current: 1,
      size: 10,
      total: 0,
      records: [],
      filters: createDefaultFilters(),
      detailVisible: false,
      detailRecord: null,
      rejectionTypeOptions: Object.keys(REJECTION_TYPE_MAP).map(key => ({
        value: key,
        label: REJECTION_TYPE_MAP[key].label
      }))
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      try {
        const dateRange = Array.isArray(this.filters.dateRange) ? this.filters.dateRange : []
        const data = await http.get('/admin/api/security/rejections', {
          params: {
            current: this.current,
            size: this.size,
            rejectionType: this.filters.rejectionType || undefined,
            requestPath: this.filters.requestPath || undefined,
            clientIp: this.filters.clientIp || undefined,
            idDevice: this.filters.idDevice || undefined,
            rejectReason: this.filters.rejectReason || undefined,
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
    openDetail(row) {
      this.detailRecord = row
      this.detailVisible = true
    },
    rejectionTypeMeta(value) {
      if (value && REJECTION_TYPE_MAP[value]) {
        return REJECTION_TYPE_MAP[value]
      }
      return { label: value || '--', type: 'info' }
    },
    formatDateTime(value) {
      if (!value) return '--'
      return String(value).replace('T', ' ').replace(/\.\d+$/, '')
    }
  }
}
</script>

<style scoped>
.sec-filter-grid {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.filter-select {
  width: 160px;
}

.filter-input {
  width: 160px;
}

.filter-date {
  width: 280px;
}

.sec-method {
  display: inline-block;
  padding: 1px 6px;
  border-radius: 3px;
  background: #ecf5ff;
  color: #409eff;
  font-size: 11px;
  font-weight: 600;
  margin-right: 6px;
  font-family: monospace;
}

.sec-path {
  font-family: monospace;
  font-size: 12px;
  color: #606266;
}

.sec-reason {
  color: #e6a23c;
  font-weight: 500;
}

.sec-detail {
  font-family: monospace;
  font-size: 12px;
  color: #909399;
  word-break: break-all;
}

.detail-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 12px;
}

.detail-card {
  padding: 10px 12px;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  background: #fafafa;
}

.detail-card--full {
  grid-column: 1 / -1;
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
</style>
