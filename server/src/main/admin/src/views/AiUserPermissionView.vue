<template>
  <div class="page-surface">
    <section class="permission-summary" aria-label="AI 使用权限汇总">
      <div class="permission-summary__item">
        <span>机构人员</span>
        <strong>{{ summary.total }}</strong>
      </div>
      <div class="permission-summary__item permission-summary__item--enabled">
        <span>已授权</span>
        <strong>{{ summary.configuredCount }}</strong>
      </div>
      <div class="permission-summary__item">
        <span>未授权</span>
        <strong>{{ summary.unconfiguredCount }}</strong>
      </div>
      <div class="permission-summary__item permission-summary__item--risk" title="BBP 已停用、状态未知或已移出机构目录，但 PCIE 仍处于授权状态">
        <span>风险授权</span>
        <div class="permission-summary__value">
          <strong>{{ summary.riskAuthorizedCount }}</strong>
          <el-button
            v-if="manageable && summary.riskAuthorizedCount > 0"
            type="text"
            :loading="cleanupUpdating"
            @click="revokeRiskPermissions"
          >一键撤销</el-button>
        </div>
      </div>
    </section>

    <section class="page-section page-section--padded page-section--toolbar">
      <div class="page-toolbar__filters permission-filters">
        <el-select
          v-model="filters.orgId"
          filterable
          :disabled="!systemAdmin"
          aria-label="机构"
          placeholder="请选择机构…"
          class="org-select"
          @change="handleOrgChange"
        >
          <el-option
            v-for="org in organizations"
            :key="org.id"
            :label="org.name || org.fullName || org.code || org.id"
            :value="org.id"
          />
        </el-select>
        <el-input
          v-model.trim="filters.keyword"
          clearable
          aria-label="人员关键词"
          placeholder="输入工号、姓名或科室…"
          class="search-input"
          @keyup.enter.native="search"
        />
        <el-select v-model="filters.configured" clearable aria-label="授权状态" placeholder="授权状态" class="status-select">
          <el-option label="已授权" :value="true" />
          <el-option label="未授权" :value="false" />
        </el-select>
        <el-select v-model="filters.departmentId" clearable filterable aria-label="科室" placeholder="全部科室" class="department-select">
          <el-option v-for="department in departments" :key="department.id" :label="department.name" :value="department.id" />
        </el-select>
        <el-select v-model="filters.active" clearable aria-label="BBP 人员状态" placeholder="BBP状态" class="status-select">
          <el-option label="启用" :value="true" />
          <el-option label="停用" :value="false" />
        </el-select>
        <el-button type="primary" icon="el-icon-search" @click="search">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-tag size="small" :type="permissionModeType">
        人员来自 BBP · 权限由 PCIE 管理 · {{ permissionModeLabel }}
      </el-tag>
    </section>

    <section class="page-section page-section--table">
      <div v-if="manageable" class="permission-batch-bar" aria-label="批量权限操作">
        <span>已选择 {{ selectedRecords.length }} 人</span>
        <el-button
          size="small"
          type="primary"
          :disabled="!selectedRecords.length || selectedUnavailableCount > 0"
          :loading="batchUpdating"
          :title="selectedUnavailableCount > 0 ? '选中人员中包含 BBP 未明确启用的人员，不能批量授予' : '批量授予 AI 使用权限'"
          @click="batchUpdate(true)"
        >批量授予</el-button>
        <el-button
          size="small"
          :disabled="!selectedRecords.length"
          :loading="batchUpdating"
          @click="batchUpdate(false)"
        >批量撤销</el-button>
      </div>

      <div v-if="loadError" class="permission-error" role="alert">
        <el-alert :title="loadError" type="error" show-icon :closable="false" />
        <el-button size="small" @click="loadData">重新加载</el-button>
      </div>

      <el-table
        :key="manageable ? 'manageable' : 'readonly'"
        ref="permissionTable"
        :data="records"
        :row-key="personKey"
        v-loading="loading"
        :empty-text="emptyText"
        @selection-change="handleSelectionChange"
      >
        <el-table-column v-if="manageable" type="selection" width="48" />
        <el-table-column label="工号" min-width="140">
          <template slot-scope="{ row }"><code-tag :value="row.personCd || row.userId || row.personId" /></template>
        </el-table-column>
        <el-table-column prop="personName" label="人员姓名" min-width="140" />
        <el-table-column label="所属机构" min-width="180">
          <template slot-scope="{ row }">{{ row.orgName || row.orgId || '--' }}</template>
        </el-table-column>
        <el-table-column label="科室" min-width="180">
          <template slot-scope="{ row }">{{ row.departmentName || row.departmentId || '--' }}</template>
        </el-table-column>
        <el-table-column label="人员类型 / 职称" min-width="180">
          <template slot-scope="{ row }">
            {{ [row.personTypeText || row.personType, row.titleTypeText || row.titleType].filter(Boolean).join(' / ') || '--' }}
          </template>
        </el-table-column>
        <el-table-column label="BBP状态" width="100">
          <template slot-scope="{ row }">
            <status-pill
              :tone="row.active === true ? 'success' : (row.active === false ? 'muted' : 'warning')"
              :label="row.active === true ? '启用' : (row.active === false ? '停用' : '未知')"
            />
          </template>
        </el-table-column>
        <el-table-column label="AI权限" width="110">
          <template slot-scope="{ row }">
            <status-pill :tone="row.configured ? 'success' : 'muted'" :label="row.configured ? '已授权' : '未授权'" />
          </template>
        </el-table-column>
        <el-table-column label="最近变更" min-width="180" show-overflow-tooltip>
          <template slot-scope="{ row }">
            <span v-if="row.updateTime">{{ row.operatorUserName || '--' }} · {{ formatDateTime(row.updateTime) }}</span>
            <span v-else>--</span>
          </template>
        </el-table-column>
        <el-table-column v-if="manageable" label="操作" width="110" fixed="right">
          <template slot-scope="{ row }">
            <table-action
              :danger="row.configured"
              :disabled="isUpdating(row) || (row.active !== true && !row.configured)"
              :aria-label="permissionActionLabel(row)"
              :title="permissionActionTitle(row)"
              @click="togglePermission(row)"
            >
              {{ isUpdating(row) ? '处理中…' : (row.configured ? '撤销' : '授予') }}
            </table-action>
          </template>
        </el-table-column>
      </el-table>
      <p class="permission-scroll-hint">表格可左右滑动查看完整人员信息和操作。</p>

      <div class="page-footer">
        <el-pagination
          background
          layout="total, sizes, prev, pager, next"
          :current-page.sync="current"
          :page-size="size"
          :page-sizes="[20, 50, 100]"
          :total="filteredTotal"
          @current-change="handlePageChange"
          @size-change="handleSizeChange"
        />
      </div>
    </section>
  </div>
</template>

<script>
import http from '../api/http'
import { CodeTag, StatusPill, TableAction } from '../components/ui'
import { getAdminUser } from '../utils/auth'

function createFilters() {
  return {
    orgId: '',
    keyword: '',
    configured: '',
    departmentId: '',
    active: ''
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
      loadError: '',
      requestSequence: 0,
      updatingPersonIds: {},
      batchUpdating: false,
      cleanupUpdating: false,
      manageable: false,
      systemAdmin: false,
      selectedRecords: [],
      organizations: [],
      departments: [],
      records: [],
      filteredTotal: 0,
      current: 1,
      size: 20,
      filters: createFilters(),
      summary: {
        total: 0,
        configuredCount: 0,
        unconfiguredCount: 0,
        riskAuthorizedCount: 0
      }
    }
  },
  computed: {
    permissionModeLabel() {
      if (!this.filters.orgId) {
        return '请选择机构'
      }
      return this.manageable ? '可维护' : '只读'
    },
    permissionModeType() {
      return this.filters.orgId && this.manageable ? 'success' : 'info'
    },
    selectedUnavailableCount() {
      return this.selectedRecords.filter(row => row.active !== true).length
    },
    emptyText() {
      if (!this.filters.orgId) {
        return '请选择机构'
      }
      if (this.loadError) {
        return '加载失败，请重新加载'
      }
      if (this.filters.keyword || this.filters.configured !== '' || this.filters.departmentId || this.filters.active !== '') {
        return '未找到符合条件的人员'
      }
      return '当前机构暂无人员'
    }
  },
  async mounted() {
    const user = getAdminUser()
    const roles = user && Array.isArray(user.roles) ? user.roles : []
    this.systemAdmin = roles.includes('SYSTEM_ADMIN')
    await this.loadOrganizations()
    await this.loadData()
  },
  methods: {
    async loadOrganizations() {
      try {
        const data = await http.get('/admin/api/bbp/organizations')
        this.organizations = Array.isArray(data) ? data : []
        if (!this.systemAdmin && !this.filters.orgId && this.organizations.length) {
          this.filters.orgId = this.organizations[0].id
        }
      } catch (error) {
        this.$message.error((error && error.message) || '加载机构目录失败')
      }
    },
    async loadData() {
      const requestId = ++this.requestSequence
      const orgId = this.filters.orgId
      this.records = []
      this.selectedRecords = []
      this.summary = { total: 0, configuredCount: 0, unconfiguredCount: 0, riskAuthorizedCount: 0 }
      this.loadError = ''
      if (!this.filters.orgId) {
        this.manageable = false
        this.filteredTotal = 0
        this.loading = false
        return
      }
      this.loading = true
      try {
        const data = await http.get('/admin/api/ai-user-permissions', {
          params: {
            orgId,
            keyword: this.filters.keyword || undefined,
            configured: this.filters.configured === '' ? undefined : this.filters.configured,
            departmentId: this.filters.departmentId || undefined,
            active: this.filters.active === '' ? undefined : this.filters.active,
            current: this.current,
            size: this.size
          }
        })
        if (requestId !== this.requestSequence || orgId !== this.filters.orgId) {
          return
        }
        this.records = data.records || []
        this.manageable = Boolean(data.manageable)
        this.filteredTotal = data.filteredTotal || 0
        this.departments = data.departments || []
        this.summary = {
          total: data.total || 0,
          configuredCount: data.configuredCount || 0,
          unconfiguredCount: data.unconfiguredCount || 0,
          riskAuthorizedCount: data.riskAuthorizedCount || 0
        }
      } catch (error) {
        if (requestId !== this.requestSequence) {
          return
        }
        this.filteredTotal = 0
        this.loadError = (error && error.message) || '加载 AI 使用权限失败'
        this.$message.error(this.loadError)
      } finally {
        if (requestId === this.requestSequence) {
          this.loading = false
        }
      }
    },
    handleOrgChange() {
      this.current = 1
      this.departments = []
      this.filteredTotal = 0
      this.loadData()
    },
    handlePageChange(page) {
      this.current = page
      this.selectedRecords = []
      if (this.$refs.permissionTable) {
        this.$refs.permissionTable.clearSelection()
      }
      this.loadData()
    },
    handleSizeChange(size) {
      this.size = size
      this.current = 1
      this.loadData()
    },
    handleSelectionChange(rows) {
      this.selectedRecords = Array.isArray(rows) ? rows : []
    },
    search() {
      this.current = 1
      this.loadData()
    },
    reset() {
      const orgId = this.filters.orgId
      this.filters = createFilters()
      this.filters.orgId = orgId
      this.current = 1
      this.loadData()
    },
    togglePermission(row) {
      if (!this.manageable || this.isUpdating(row)) {
        return
      }
      const enabled = !row.configured
      const action = enabled ? '授予' : '撤销'
      const personName = row.personName || '--'
      const personCd = row.personCd || row.userId || row.personId || '--'
      const orgName = row.orgName || row.orgId || this.filters.orgId
      this.$confirm(`确认${action}「${personName}」（工号：${personCd}，机构：${orgName}）的 AI 使用权限吗？`, 'AI 使用权限', {
        type: enabled ? 'info' : 'warning'
      }).then(async () => {
        const personId = this.personKey(row)
        this.$set(this.updatingPersonIds, personId, true)
        try {
          await http.put(`/admin/api/ai-user-permissions/${encodeURIComponent(this.personKey(row))}`, {
            orgId: this.filters.orgId,
            enabled
          })
          this.$message.success(`${action}成功`)
          await this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || `${action}失败`)
        } finally {
          this.$delete(this.updatingPersonIds, personId)
        }
      }).catch(() => {})
    },
    batchUpdate(enabled) {
      if (!this.manageable || !this.selectedRecords.length || this.batchUpdating) {
        return
      }
      if (enabled && this.selectedUnavailableCount > 0) {
        this.$message.warning('选中人员中包含 BBP 未明确启用的人员，请取消选择后重试')
        return
      }
      const action = enabled ? '授予' : '撤销'
      const organization = this.organizations.find(item => item.id === this.filters.orgId)
      const orgName = organization
        ? (organization.name || organization.fullName || organization.code || organization.id)
        : this.filters.orgId
      const personIds = this.selectedRecords.map(this.personKey).filter(Boolean)
      this.$confirm(`确认在「${orgName}」对选中的 ${personIds.length} 人批量${action} AI 使用权限吗？`, '批量 AI 使用权限', {
        type: enabled ? 'info' : 'warning'
      }).then(async () => {
        this.batchUpdating = true
        try {
          const result = await http.put('/admin/api/ai-user-permissions/batch', {
            orgId: this.filters.orgId,
            personIds,
            enabled
          })
          this.$message.success(`${action}完成：变更 ${result.changedCount || 0} 人，未变化 ${result.unchangedCount || 0} 人`)
          await this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || `批量${action}失败`)
        } finally {
          this.batchUpdating = false
        }
      }).catch(() => {})
    },
    revokeRiskPermissions() {
      if (!this.manageable || !this.summary.riskAuthorizedCount || this.cleanupUpdating) {
        return
      }
      const organization = this.organizations.find(item => item.id === this.filters.orgId)
      const orgName = organization
        ? (organization.name || organization.fullName || organization.code || organization.id)
        : this.filters.orgId
      this.$confirm(`确认撤销「${orgName}」全部 ${this.summary.riskAuthorizedCount} 条风险授权吗？`, '清理风险授权', {
        type: 'warning'
      }).then(async () => {
        this.cleanupUpdating = true
        try {
          const result = await http.put('/admin/api/ai-user-permissions/revoke-risk', {
            orgId: this.filters.orgId
          })
          this.$message.success(`风险授权清理完成：已撤销 ${result.changedCount || 0} 人`)
          await this.loadData()
        } catch (error) {
          this.$message.error((error && error.message) || '风险授权清理失败')
        } finally {
          this.cleanupUpdating = false
        }
      }).catch(() => {})
    },
    personKey(row) {
      return row.personId || row.userId || row.personCd || ''
    },
    isUpdating(row) {
      return Boolean(this.updatingPersonIds[this.personKey(row)])
    },
    permissionActionLabel(row) {
      const action = row.configured ? '撤销' : '授予'
      return `${action}${row.personName || row.personCd || '该人员'}的 AI 使用权限`
    },
    permissionActionTitle(row) {
      if (row.active !== true && !row.configured) {
        return row.active === false
          ? 'BBP 人员已停用，不能授予 AI 使用权限'
          : 'BBP 人员状态未知，不能授予 AI 使用权限'
      }
      return this.permissionActionLabel(row)
    },
    formatDateTime(value) {
      if (!value) {
        return '--'
      }
      const date = new Date(value)
      if (Number.isNaN(date.getTime())) {
        return String(value).replace('T', ' ').slice(0, 16)
      }
      const pad = number => String(number).padStart(2, '0')
      return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(date.getHours())}:${pad(date.getMinutes())}`
    }
  }
}
</script>

<style scoped>
.permission-summary {
  display: grid;
  grid-template-columns: repeat(4, minmax(0, 1fr));
  gap: 12px;
  margin-bottom: 12px;
}

.permission-summary__item {
  display: flex;
  align-items: baseline;
  justify-content: space-between;
  padding: 14px 18px;
  border: 1px solid var(--border-color-base);
  border-radius: var(--radius-card);
  background: var(--bg-white);
  color: var(--color-text-secondary);
}

.permission-summary__item strong {
  font-size: 24px;
  color: var(--color-text-primary);
}

.permission-summary__item--enabled strong {
  color: var(--color-primary);
}

.permission-summary__item--risk strong {
  color: var(--color-danger);
}

.permission-summary__value {
  display: flex;
  align-items: baseline;
  gap: 8px;
}

.permission-filters {
  flex-wrap: wrap;
}

.org-select {
  width: 280px;
}

.status-select {
  width: 140px;
}

.department-select {
  width: 180px;
}

.permission-error {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px 0;
}

.permission-batch-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 52px;
  padding: 8px 16px;
  border-bottom: 1px solid var(--border-color-light);
  color: var(--color-text-secondary);
  font-size: 13px;
}

.permission-error .el-alert {
  flex: 1;
}

.permission-scroll-hint {
  display: none;
  margin: 8px 16px 0;
  color: var(--color-text-secondary);
  font-size: 12px;
}

@media (max-width: 1200px) {
  .permission-summary {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (max-width: 900px) {
  .permission-scroll-hint {
    display: block;
  }
}

@media (max-width: 720px) {
  .permission-summary {
    grid-template-columns: 1fr;
  }
}
</style>
