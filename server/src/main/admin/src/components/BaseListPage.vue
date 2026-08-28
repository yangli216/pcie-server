<template>
  <div class="page-surface">
    <section class="page-section page-section--padded page-section--toolbar">
      <el-input
        v-model="keyword"
        clearable
        placeholder="请输入关键字…"
        class="search-input"
        @keyup.enter.native="loadData"
      />
      <el-button type="primary" @click="loadData">查询</el-button>
      <el-button @click="reset">重置</el-button>
    </section>

    <section class="page-section page-section--table">
      <el-table :data="records" v-loading="loading" class="admin-table">
        <el-table-column
          v-for="column in columns"
          :key="column.prop"
          :prop="column.prop"
          :label="column.label"
          :min-width="column.minWidth || 120"
        />
      </el-table>

      <div class="footer">
        <AdminPagination
          :current.sync="current"
          :size.sync="size"
          :total="total"
          @change="loadData"
        />
      </div>
    </section>
  </div>
</template>

<script>
import http from '../api/http'

export default {
  name: 'BaseListPage',
  props: {
    title: {
      type: String,
      default: ''
    },
    resource: {
      type: String,
      required: true
    },
    columns: {
      type: Array,
      default: () => []
    }
  },
  data() {
    return {
      loading: false,
      keyword: '',
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
    async loadData() {
      this.loading = true
      try {
        const data = await http.get(this.resource, {
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
    }
  }
}
</script>

<style scoped>
.search-input {
  width: 320px;
}

.footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 16px;
}
</style>
