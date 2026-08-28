<template>
  <div :class="shellClass">
    <AdminSidebar
      id="admin-sidebar"
      class="admin-shell__sidebar"
      :menu-items="menuItems"
      :collapsed="sidebarCollapsed && !isNarrowViewport"
      @navigate="closeMobileSidebar"
    />

    <button
      v-if="mobileSidebarOpen"
      type="button"
      class="admin-shell__scrim"
      aria-label="关闭侧边导航"
      @click="closeMobileSidebar"
    ></button>

    <section class="admin-shell__main">
      <AdminTopbar
        class="admin-shell__topbar"
        :title="routeTitle"
        :current-user-name="currentUserName"
        :current-user-initial="currentUserInitial"
        :can-change-password="canChangePassword"
        :sidebar-collapsed="sidebarCollapsed && !isNarrowViewport"
        :mobile-sidebar-open="mobileSidebarOpen"
        :narrow-viewport="isNarrowViewport"
        :fullscreen-active="fullscreenActive"
        @toggle-sidebar="toggleSidebar"
        @toggle-fullscreen="toggleFullscreen"
        @open-password-dialog="$emit('open-password-dialog')"
        @logout="$emit('logout')"
      />

      <AdminTabBar
        class="admin-shell__tabs"
        :visited-tags="visitedTags"
        @close-tag="$emit('close-tag', $event)"
      />

      <main class="admin-shell__content" tabindex="-1">
        <slot />
      </main>
    </section>
  </div>
</template>

<script>
import AdminSidebar from './AdminSidebar.vue'
import AdminTabBar from './AdminTabBar.vue'
import AdminTopbar from './AdminTopbar.vue'

export default {
  name: 'AdminLayout',
  components: {
    AdminSidebar,
    AdminTabBar,
    AdminTopbar
  },
  props: {
    menuItems: {
      type: Array,
      required: true
    },
    routeTitle: {
      type: String,
      default: ''
    },
    visitedTags: {
      type: Array,
      default: () => []
    },
    currentUserName: {
      type: String,
      default: '管理员'
    },
    currentUserInitial: {
      type: String,
      default: '管'
    },
    canChangePassword: {
      type: Boolean,
      default: true
    }
  },
  data() {
    return {
      sidebarCollapsed: false,
      mobileSidebarOpen: false,
      isNarrowViewport: false,
      fullscreenActive: false
    }
  },
  computed: {
    shellClass() {
      return [
        'admin-shell',
        {
          'admin-shell--collapsed': this.sidebarCollapsed && !this.isNarrowViewport,
          'admin-shell--mobile-open': this.mobileSidebarOpen
        }
      ]
    }
  },
  watch: {
    '$route.fullPath'() {
      this.closeMobileSidebar()
    }
  },
  mounted() {
    this.handleViewportChange()
    if (typeof window !== 'undefined') {
      window.addEventListener('resize', this.handleViewportChange)
    }
    if (typeof document !== 'undefined') {
      document.addEventListener('fullscreenchange', this.syncFullscreenState)
    }
  },
  beforeDestroy() {
    if (typeof window !== 'undefined') {
      window.removeEventListener('resize', this.handleViewportChange)
    }
    if (typeof document !== 'undefined') {
      document.removeEventListener('fullscreenchange', this.syncFullscreenState)
    }
  },
  methods: {
    handleViewportChange() {
      if (typeof window === 'undefined') {
        return
      }
      this.isNarrowViewport = window.innerWidth <= 760
      if (!this.isNarrowViewport) {
        this.mobileSidebarOpen = false
      }
    },
    toggleSidebar() {
      if (this.isNarrowViewport) {
        this.mobileSidebarOpen = !this.mobileSidebarOpen
        return
      }
      this.sidebarCollapsed = !this.sidebarCollapsed
    },
    closeMobileSidebar() {
      this.mobileSidebarOpen = false
    },
    async toggleFullscreen() {
      if (typeof document === 'undefined') {
        return
      }
      try {
        if (document.fullscreenElement) {
          await document.exitFullscreen()
        } else if (document.documentElement && document.documentElement.requestFullscreen) {
          await document.documentElement.requestFullscreen()
        }
      } catch (error) {
        this.$message.error('浏览器未允许切换全屏')
      } finally {
        this.syncFullscreenState()
      }
    },
    syncFullscreenState() {
      if (typeof document === 'undefined') {
        return
      }
      this.fullscreenActive = Boolean(document.fullscreenElement)
    }
  }
}
</script>
