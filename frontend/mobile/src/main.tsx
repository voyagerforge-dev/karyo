import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import { registerSW } from 'virtual:pwa-register'
import {
  keycloak,
  initOptions,
  readAuthenticationCallbackState,
  restoreAuthenticationRoute,
} from '@/lib/keycloak'
import { startSyncTriggers } from '@/lib/offline/sync'
import '@/index.css'

const authenticationCallbackState = readAuthenticationCallbackState()

keycloak.init(initOptions).then(async (authenticated) => {
  if (authenticated) restoreAuthenticationRoute(authenticationCallbackState)
  registerSW({ immediate: true })
  if (authenticated) startSyncTriggers()
  const { default: App } = await import('@/App')
  createRoot(document.getElementById('root')!).render(<StrictMode><App /></StrictMode>)
}).catch((error) => {
  console.error('[Keycloak] Floor authentication failed:', error)
  document.getElementById('root')!.innerHTML = '<p style="padding:24px;color:#fff">Auth init failed. Reload.</p>'
})
