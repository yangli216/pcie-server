<template>
  <div class="page-surface device-page">
    <section class="page-section page-section--padded">
      <div class="page-toolbar__filters">
        <el-input
          v-model="keyword"
          clearable
          placeholder="输入终端编码或名称…"
          class="search-input"
          @keyup.enter.native="loadData"
        />
        <el-button type="primary" icon="el-icon-search" @click="loadData">查询</el-button>
        <el-button @click="reset">重置</el-button>
      </div>
      <el-button type="primary" icon="el-icon-plus" @click="openCreate">新增令牌</el-button>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading">
        <el-table-column label="终端编码" min-width="140">
          <template slot-scope="{ row }"><code-tag :value="row.cdDevice" /></template>
        </el-table-column>
        <el-table-column prop="naDevice" label="终端名称" min-width="160" />
        <el-table-column label="用户姓名" min-width="110">
          <template slot-scope="{ row }">{{ row.naUser || '--' }}</template>
        </el-table-column>
        <el-table-column prop="naOrg" label="所属机构" min-width="160" />
        <el-table-column prop="naRegion" label="所属区域" min-width="140" />
        <el-table-column label="状态" width="100">
          <template slot-scope="{ row }">
            <status-pill :tone="statusTone(statusMeta(row.sdStatus).type)" :label="statusMeta(row.sdStatus).label" />
          </template>
        </el-table-column>
        <el-table-column label="访问令牌" min-width="160">
          <template slot-scope="{ row }"><code-tag :value="row.deviceTokenMasked" /></template>
        </el-table-column>
        <el-table-column label="客户端版本" width="120">
          <template slot-scope="{ row }"><code-tag :value="row.clientVersion" /></template>
        </el-table-column>
        <el-table-column label="注册 IP" min-width="130">
          <template slot-scope="{ row }"><code-tag :value="row.registerIp" /></template>
        </el-table-column>
        <el-table-column label="最近 IP" min-width="130">
          <template slot-scope="{ row }"><code-tag :value="row.lastSeenIp" /></template>
        </el-table-column>
        <el-table-column label="最后心跳" min-width="160">
          <template slot-scope="{ row }">
            {{ formatDateTime(row.dtLastHeartbeat) }}
          </template>
        </el-table-column>
        <el-table-column label="注册时间" min-width="160">
          <template slot-scope="{ row }">
            {{ formatDateTime(row.dtRegistered) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="180" fixed="right">
          <template slot-scope="{ row }">
            <div class="table-actions">
              <table-action @click="openEdit(row)">编辑</table-action>
              <table-action danger @click="disableRecord(row)">停用</table-action>
              <table-action danger @click="deleteRecord(row)">删除</table-action>
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

    <el-dialog v-if="dialogVisible" :title="dialogTitle" :visible.sync="dialogVisible" width="720px" @closed="resetForm">
      <el-form ref="formRef" :model="form" :rules="rules" label-position="top">
        <div class="form-grid">
          <el-form-item label="终端编码" prop="cdDevice">
            <el-input v-model.trim="form.cdDevice" maxlength="128" />
          </el-form-item>
          <el-form-item label="终端名称" prop="naDevice">
            <el-input v-model.trim="form.naDevice" maxlength="128" />
          </el-form-item>
          <el-form-item label="所属机构" prop="idOrg">
            <el-select v-model="form.idOrg" filterable placeholder="请选择机构…" @change="syncRegionByOrg">
              <el-option v-for="item in orgOptions" :key="item.idOrg" :label="item.naOrg" :value="item.idOrg" />
            </el-select>
          </el-form-item>
          <el-form-item label="所属区域">
            <el-input :value="currentRegionName" disabled />
          </el-form-item>
          <el-form-item label="状态" prop="sdStatus">
            <segmented-switch v-model="form.sdStatus" :options="statusOptions" />
          </el-form-item>
          <el-form-item label="客户端版本">
            <el-input v-model.trim="form.clientVersion" maxlength="64" />
          </el-form-item>
          <el-form-item label="操作系统" class="form-span-2">
            <el-input v-model.trim="form.osInfo" maxlength="500" />
          </el-form-item>
        </div>
      </el-form>
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
import { buildLabelMap, deviceStatusOptions, findStatusMeta, formatDateTime, statusTone } from '../utils/admin'
import { CodeTag, SegmentedSwitch, StatusPill, TableAction } from '../components/ui'

function createDefaultForm() {
  return {
    idDevice: '',
    cdDevice: '',
    naDevice: '',
    idOrg: '',
    idRegion: '',
    clientVersion: '',
    osInfo: '',
    sdStatus: '0'
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
      dialogVisible: false,
      dialogMode: 'create',
      keyword: '',
      current: 1,
      size: 10,
      total: 0,
      records: [],
      regionMap: {},
      orgOptions: [],
      statusOptions: deviceStatusOptions,
      form: createDefaultForm(),
      rules: {
        cdDevice: [{ required: true, message: '请输入终端编码', trigger: 'blur' }],
        idOrg: [{ required: true, message: '请选择所属机构', trigger: 'change' }]
      }
    }
  },
  computed: {
    dialogTitle() {
      return this.dialogMode === 'create' ? '新增令牌' : '编辑令牌'
    },
    currentRegionName() {
      const org = this.orgOptions.find(item => item.idOrg === this.form.idOrg)
      return org ? this.regionMap[org.idRegion] || org.idRegion || '--' : '--'
    }
  },
  async mounted() {
    await this.loadReferences()
    this.loadData()
  },
  methods: {
    formatDateTime,
    statusTone,
    statusMeta(value) {
      return findStatusMeta(deviceStatusOptions, value)
    },
    async loadReferences() {
      const [regions, orgs] = await Promise.all([fetchRegions(), fetchOrgs()])
      this.regionMap = buildLabelMap(regions, 'idRegion', 'naRegion')
      this.orgOptions = orgs
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/devices', {
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
      this.dialogVisible = true
    },
    openEdit(row) {
      this.dialogMode = 'edit'
      this.form = {
        idDevice: row.idDevice,
        cdDevice: row.cdDevice,
        naDevice: row.naDevice,
        idOrg: row.idOrg,
        idRegion: row.idRegion,
        clientVersion: row.clientVersion || '',
        osInfo: row.osInfo || '',
        sdStatus: row.sdStatus || '0'
      }
      this.dialogVisible = true
    },
    syncRegionByOrg(idOrg) {
      const org = this.orgOptions.find(item => item.idOrg === idOrg)
      this.form.idRegion = org ? org.idRegion : ''
    },
    resetForm() {
      this.form = createDefaultForm()
      if (this.$refs.formRef) {
        this.$refs.formRef.resetFields()
      }
    },
    submitForm() {
      this.$refs.formRef.validate(async valid => {
        if (!valid) {
          return
        }
        this.saving = true
        try {
          const payload = {
            cdDevice: this.form.cdDevice,
            naDevice: this.form.naDevice,
            idOrg: this.form.idOrg,
            clientVersion: this.form.clientVersion,
            osInfo: this.form.osInfo,
            sdStatus: this.form.sdStatus
          }
          if (this.dialogMode === 'create') {
            await http.post('/admin/api/devices', payload)
          } else {
            await http.put(`/admin/api/devices/${this.form.idDevice}`, payload)
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
    disableRecord(row) {
      this.$confirm(`确认停用令牌「${row.naDevice || row.cdDevice}」吗？`, '提示', {
        type: 'warning'
      }).then(async () => {
        try {
          await http.post(`/admin/api/devices/${row.idDevice}/disable`)
          this.$message.success('停用成功')
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || '停用失败')
        }
      }).catch(() => {})
    },
    deleteRecord(row) {
      const label = row.naDevice || row.cdDevice
      this.$confirm(`删除令牌「${label}」后，该终端可重新注册并领取新令牌。确认删除？`, '删除令牌', {
        type: 'warning',
        confirmButtonText: '删除'
      }).then(async () => {
        try {
          await http.delete(`/admin/api/devices/${row.idDevice}`)
          this.$message.success('删除成功')
          this.loadData()
        } catch (error) {
          this.$message.error(error.message || '删除失败')
        }
      }).catch(() => {})
    }
  }
}
</script>

<style scoped>
.device-page .page-section--padded {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}
</style>
