export function registerRouteErrorHandler(router, notify) {
  router.onError(() => {
    notify({
      message: '页面加载失败，请刷新页面后重试；如仍无法打开，请联系管理员。',
      type: 'error',
      duration: 6000,
      showClose: true
    })
  })
}
