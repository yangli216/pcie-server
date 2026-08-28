<template>
  <header class="admin-topbar">
    <div class="admin-topbar__leading">
      <button
        type="button"
        class="admin-topbar__icon-button"
        :aria-label="sidebarButtonLabel"
        :aria-expanded="mobileSidebarOpen ? 'true' : 'false'"
        aria-controls="admin-sidebar"
        @click="$emit('toggle-sidebar')"
      >
        <i :class="sidebarIcon" aria-hidden="true"></i>
      </button>
      <span class="admin-topbar__title">{{ title || '管理端' }}</span>
    </div>

    <div class="admin-topbar__actions">
      <button
        type="button"
        class="admin-topbar__icon-button"
        :aria-label="fullscreenActive ? '退出全屏' : '切换全屏'"
        @click="$emit('toggle-fullscreen')"
      >
        <i :class="fullscreenActive ? 'el-icon-copy-document' : 'el-icon-full-screen'" aria-hidden="true"></i>
      </button>
      <div class="admin-user-menu">
        <button type="button" class="admin-user-menu__trigger" aria-label="打开账号菜单">
          <span class="admin-user-menu__avatar">{{ currentUserInitial }}</span>
          <i class="el-icon-arrow-down admin-user-menu__chevron" aria-hidden="true"></i>
        </button>
        <div class="admin-user-menu__panel">
          <div class="admin-user-menu__name">{{ currentUserName }}</div>
          <button v-if="canChangePassword" type="button" @click="$emit('open-password-dialog')">修改密码</button>
          <button type="button" @click="$emit('logout')">退出登录</button>
        </div>
      </div>
    </div>
  </header>
</template>

<script>
export default {
  name: 'AdminTopbar',
  props: {
    title: {
      type: String,
      default: ''
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
    },
    sidebarCollapsed: {
      type: Boolean,
      default: false
    },
    mobileSidebarOpen: {
      type: Boolean,
      default: false
    },
    narrowViewport: {
      type: Boolean,
      default: false
    },
    fullscreenActive: {
      type: Boolean,
      default: false
    }
  },
  computed: {
    sidebarIcon() {
      if (this.mobileSidebarOpen) {
        return 'el-icon-close'
      }
      return this.sidebarCollapsed ? 'el-icon-s-unfold' : 'el-icon-s-fold'
    },
    sidebarButtonLabel() {
      if (this.mobileSidebarOpen) {
        return '关闭侧边导航'
      }
      if (this.narrowViewport) {
        return '打开侧边导航'
      }
      return this.sidebarCollapsed ? '展开侧边导航' : '收起侧边导航'
    }
  }
}
</script>
