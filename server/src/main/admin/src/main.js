import Vue from 'vue'
import App from './App.vue'
import router from './router'
import { AdminPagination } from './components/ui'
import './plugins/element'
import './styles/global.css'

Vue.config.productionTip = false
Vue.component('AdminPagination', AdminPagination)

new Vue({
  router,
  render: h => h(App)
}).$mount('#app')
