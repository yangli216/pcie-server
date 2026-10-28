<template>
  <aside class="admin-sidebar" :class="{ 'is-collapsed': collapsed }" aria-label="管理端导航">
    <div class="admin-sidebar__brand">
      <span class="admin-sidebar__brand-mark" aria-hidden="true">医</span>
      <span class="admin-sidebar__brand-text">全医慧助（PCIE）</span>
    </div>

    <nav class="admin-sidebar__nav" aria-label="主导航">
      <template v-for="item in menuItems">
        <section v-if="item.children" :key="item.id" class="admin-sidebar__group" :aria-label="item.label">
          <button
            v-if="!collapsed"
            type="button"
            class="admin-sidebar__group-toggle"
            :class="{ 'is-current': currentGroupId === item.id }"
            :aria-expanded="expandedGroupIds.includes(item.id) ? 'true' : 'false'"
            :aria-controls="`admin-menu-${item.id}`"
            @click="toggleGroup(item.id)"
          >
            <i :class="item.icon" class="admin-sidebar__link-icon" aria-hidden="true"></i>
            <span class="admin-sidebar__group-label">{{ item.label }}</span>
            <i
              class="admin-sidebar__group-chevron el-icon-arrow-right"
              :class="{ 'is-expanded': expandedGroupIds.includes(item.id) }"
              aria-hidden="true"
            ></i>
          </button>
          <div v-else class="admin-sidebar__group-divider" role="separator" :aria-label="item.label"></div>
          <div :id="`admin-menu-${item.id}`" v-show="collapsed || expandedGroupIds.includes(item.id)">
            <router-link
              v-for="page in item.children"
              :key="page.path"
              :to="page.path"
              class="admin-sidebar__link admin-sidebar__link--child"
              active-class="is-active"
              exact
              :title="collapsed ? page.label : null"
              :aria-label="page.label"
              :aria-current="$route.path === page.path ? 'page' : null"
              @click.native="$emit('navigate', page.path)"
            >
              <i :class="page.icon" class="admin-sidebar__link-icon" aria-hidden="true"></i>
              <span class="admin-sidebar__link-text">{{ page.label }}</span>
            </router-link>
          </div>
        </section>
        <router-link
          v-else
          :key="item.path"
          :to="item.path"
          class="admin-sidebar__link"
          active-class="is-active"
          exact
          :title="collapsed ? item.label : null"
          :aria-label="item.label"
          :aria-current="$route.path === item.path ? 'page' : null"
          @click.native="$emit('navigate', item.path)"
        >
          <i :class="item.icon" class="admin-sidebar__link-icon" aria-hidden="true"></i>
          <span class="admin-sidebar__link-text">{{ item.label }}</span>
        </router-link>
      </template>
    </nav>
  </aside>
</template>

<script>
export default {
  name: 'AdminSidebar',
  props: {
    menuItems: {
      type: Array,
      required: true
    },
    collapsed: {
      type: Boolean,
      default: false
    }
  },
  data() {
    return { expandedGroupIds: [] }
  },
  computed: {
    currentGroupId() {
      const group = this.menuItems.find(item => item.children &&
        item.children.some(page => page.path === this.$route.path))
      return group ? group.id : ''
    }
  },
  watch: {
    '$route.path': { immediate: true, handler: 'expandCurrentGroup' },
    menuItems: { immediate: true, handler: 'expandCurrentGroup' }
  },
  methods: {
    expandCurrentGroup() {
      if (this.currentGroupId && !this.expandedGroupIds.includes(this.currentGroupId)) {
        this.expandedGroupIds.push(this.currentGroupId)
      }
    },
    toggleGroup(id) {
      if (this.expandedGroupIds.includes(id)) {
        this.expandedGroupIds = this.expandedGroupIds.filter(value => value !== id)
      } else {
        this.expandedGroupIds.push(id)
      }
    }
  }
}
</script>
