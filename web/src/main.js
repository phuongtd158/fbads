import { createApp } from 'vue'
import '@fontsource-variable/inter'
import './styles/tokens.css'
import './styles/base.css'
import App from './App.vue'
import router from './router'

const app = createApp(App)
app.config.errorHandler = (err, instance, info) => {
  console.error('[vue]', info, instance && instance.$options && (instance.$options.__name || instance.$options.name), err && err.stack)
}
app.use(router).mount('#app')
