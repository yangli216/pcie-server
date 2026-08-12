<template>
  <div class="page-surface client-usage-page">
    <admin-filter-bar>
      <div class="filter-row">
        <div class="filter-item">
          <div class="filter-label">搜索</div>
          <el-input
            v-model="keyword"
            class="client-usage-page__keyword"
            size="small"
            clearable
            placeholder="机构名称 / 医生姓名 / 工号 / 客户端版本"
            @keyup.enter.native="search"
            @clear="search"
          />
        </div>
        <div class="filter-actions">
          <el-button type="primary" size="small" @click="search">查询</el-button>
          <el-button size="small" @click="reset">重置</el-button>
        </div>
      </div>
      <template #actions>
        <el-button size="small" icon="el-icon-download" :loading="exporting" @click="exportData">导出全部字段</el-button>
        <el-button size="small" icon="el-icon-refresh" :loading="loading" @click="loadData">刷新</el-button>
      </template>
    </admin-filter-bar>

    <section class="page-section page-section--table">
      <div class="page-section__header">
        <div>
          <div class="page-section__title">医生使用情况</div>
          <div class="client-usage-page__count">共 {{ total }} 名医生 · 安装时间按当前版本首次交互统计</div>
        </div>
      </div>

      <el-table
        v-loading="loading"
        :data="records"
        aria-label="客户端使用情况列表"
      >
        <el-table-column label="机构名称" min-width="200">
          <template slot-scope="{ row }">{{ row.orgName || '--' }}</template>
        </el-table-column>
        <el-table-column label="医生名字" min-width="120">
          <template slot-scope="{ row }">{{ row.doctorName || '--' }}</template>
        </el-table-column>
        <el-table-column label="工号" min-width="120">
          <template slot-scope="{ row }"><code-tag :value="row.doctorWorkNo" /></template>
        </el-table-column>
        <el-table-column label="安装客户端时间" min-width="170">
          <template slot-scope="{ row }">{{ formatDateTime(row.firstInteractionTime) }}</template>
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
          layout="total, sizes, prev, pager, next"
          :current-page="current"
          :page-size="size"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </section>
  </div>
</template>

<script>
import http from '../api/http'
import { AdminFilterBar, CodeTag } from '../components/ui'
import { formatDateTime } from '../utils/admin'

export default {
  name: 'ClientUsageView',
  components: {
    AdminFilterBar,
    CodeTag
  },
  data() {
    return {
      loading: false,
      exporting: false,
      keyword: '',
      current: 1,
      size: 20,
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
    onSizeChange(size) {
      this.size = size
      this.current = 1
      this.loadData()
    },
    search() {
      this.current = 1
      this.loadData()
    },
    reset() {
      this.keyword = ''
      this.current = 1
      this.loadData()
    },
    async loadData() {
      this.loading = true
      try {
        const data = await http.get('/admin/api/client-usage', {
          params: {
            current: this.current,
            size: this.size,
            ...(this.keyword.trim() ? { keyword: this.keyword.trim() } : {})
          }
        })
        this.records = (data && data.records) || []
        this.total = (data && data.total) || 0
      } catch (error) {
        this.$message.error((error && error.message) || '加载客户端使用情况失败')
      } finally {
        this.loading = false
      }
    },
    async exportData() {
      this.exporting = true
      try {
        const blob = await http.get('/admin/api/client-usage/export', {
          params: this.keyword.trim() ? { keyword: this.keyword.trim() } : {},
          responseType: 'blob'
        })
        this.downloadBlob(blob, '医生客户端使用情况_' + new Date().toISOString().slice(0, 10) + '.xlsx')
      } catch (error) {
        this.$message.error((error && error.message) || '导出客户端使用情况失败')
      } finally {
        this.exporting = false
      }
    },
    downloadBlob(blob, filename) {
      const url = URL.createObjectURL(blob)
      const link = document.createElement('a')
      link.href = url
      link.download = filename
      document.body.appendChild(link)
      link.click()
      document.body.removeChild(link)
      URL.revokeObjectURL(url)
    }
  }
}
</script>

<style scoped>
.client-usage-page {
  display: grid;
  gap: 16px;
}

.client-usage-page__keyword {
  width: 360px;
  max-width: 100%;
}

.client-usage-page__count {
  margin-top: 4px;
  color: var(--color-text-secondary);
  font-size: 12px;
}

@media (max-width: 768px) {
  .client-usage-page__keyword {
    width: 100%;
  }
}
</style>
