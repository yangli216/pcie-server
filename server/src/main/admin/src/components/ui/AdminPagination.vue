<script>
const DEFAULT_PAGE_SIZES = [10, 20, 50, 100]

export default {
  name: 'AdminPagination',
  props: {
    current: {
      type: Number,
      default: 1
    },
    size: {
      type: Number,
      default: 10
    },
    total: {
      type: Number,
      default: 0
    },
    pageSizes: {
      type: Array,
      default: () => DEFAULT_PAGE_SIZES
    },
    compact: {
      type: Boolean,
      default: false
    },
    align: {
      type: String,
      default: 'end',
      validator: value => ['start', 'center', 'end'].includes(value)
    }
  },
  data() {
    return {
      changeScheduled: false,
      pendingChange: null
    }
  },
  computed: {
    paginationLayout() {
      return this.compact
        ? 'total, sizes, prev, pager, next'
        : 'total, sizes, prev, pager, next, jumper'
    }
  },
  methods: {
    handleCurrentChange(current) {
      this.$emit('update:current', current)
      const size = this.pendingChange ? this.pendingChange.size : this.size
      this.scheduleChange(current, size)
    },
    handleSizeChange(size) {
      this.$emit('update:size', size)
      this.$emit('update:current', 1)
      this.scheduleChange(1, size)
    },
    scheduleChange(current, size) {
      this.pendingChange = { current, size }
      if (this.changeScheduled) return

      this.changeScheduled = true
      this.$nextTick(() => {
        const change = this.pendingChange
        this.pendingChange = null
        this.changeScheduled = false
        this.$emit('change', change)
      })
    }
  }
}
</script>

<template>
  <div class="admin-pagination" :class="`admin-pagination--${align}`">
    <el-pagination
      background
      :small="compact"
      :layout="paginationLayout"
      :current-page="current"
      :page-size="size"
      :page-sizes="pageSizes"
      :pager-count="compact ? 5 : 7"
      :total="total"
      @current-change="handleCurrentChange"
      @size-change="handleSizeChange"
    />
  </div>
</template>

<style scoped>
.admin-pagination {
  display: flex;
  width: 100%;
  overflow-x: auto;
}

.admin-pagination--start :deep(.el-pagination) {
  margin-right: auto;
}

.admin-pagination--center :deep(.el-pagination) {
  margin-right: auto;
  margin-left: auto;
}

.admin-pagination--end :deep(.el-pagination) {
  margin-left: auto;
}

.admin-pagination :deep(.el-pagination) {
  flex: 0 0 auto;
  white-space: nowrap;
}
</style>
