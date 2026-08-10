<template>
  <div class="page-surface client-usage-page">
    <section class="page-section page-section--table">
      <div class="page-section__header">
        <div>
          <div class="page-section__title">客户端列表</div>
          <div class="client-usage-page__count">共 {{ total }} 条记录</div>
        </div>
        <el-button icon="el-icon-refresh" :loading="loading" @click="loadData">刷新</el-button>
      </div>

      <el-table
        v-loading="loading"
        :data="records"
        aria-label="客户端使用情况列表"
      >
        <el-table-column label="机构名称" min-width="200">
          <template slot-scope="{ row }">{{ row.naOrg || '--' }}</template>
        </el-table-column>
        <el-table-column label="医生名字" min-width="120">
          <template slot-scope="{ row }">{{ row.naUser || '--' }}</template>
        </el-table-column>
        <el-table-column label="工号" min-width="120">
          <template slot-scope="{ row }"><code-tag :value="row.doctorWorkNo" /></template>
        </el-table-column>
        <el-table-column label="安装客户端时间" min-width="170">
          <template slot-scope="{ row }">{{ formatDateTime(row.dtRegistered) }}</template>
        </el-table-column>
        <el-table-column label="当前使用的客户端版本" min-width="190">
          <template slot-scope="{ row }"><code-tag :value="row.clientVersion" /></template>
        </el-table-column>
        <el-table-column label="最近活跃时间" min-width="170">
          <template slot-scope="{ row }">{{ formatDateTime(row.lastActiveTime) }}</template>
        </el-table-column>
      </el-table>

      <div class="page-footer">
        <el-pagination
          background
          layout="total, prev, pager, next"
          :current-page="current"
          :page-size="size"
          :total="total"
          @current-change="onPageChange"
        />
      </div>
    </section>
  </div>
</template>

<script>
import http from '../api/http'
import { CodeTag } from '../components/ui'
import { formatDateTime } from '../utils/admin'

export default {
  name: 'ClientUsageView',
  components: {
    CodeTag
  },
  data() {
    return {
      loading: false,
      current: 1,
      size: 10,
      total: 0,
      records: []
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    formatDateTime,
    onPageChange(page) {
      this.current = page
      this.loadData()
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/devices', {
          params: {
            current: this.current,
            size: this.size
          }
        })
        this.records = (data && data.records) || []
        this.total = (data && data.total) || 0
      } catch (error) {
        this.$message.error((error && error.message) || '加载客户端使用情况失败')
      } finally {
        this.loading = false
      }
    }
  }
}
</script>

<style scoped>
.client-usage-page__count {
  margin-top: 4px;
  color: var(--color-text-secondary);
  font-size: 12px;
}
</style>
