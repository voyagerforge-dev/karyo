import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import {
  keycloak,
  initOptions,
  readAuthenticationCallbackState,
  restoreAuthenticationRoute,
  startLogin,
} from '@/lib/keycloak';
import '@/index.css';

/** Keycloak removes callback parameters during init, so capture the state first. */
const authenticationCallbackState = readAuthenticationCallbackState();

/** Initialize Keycloak before React renders to avoid StrictMode double initialization. */
keycloak
  .init(initOptions)
  .then(async (authenticated) => {
    if (!authenticated) {
      await startLogin();
      return;
    }
    restoreAuthenticationRoute(authenticationCallbackState);
    const { default: App } = await import('@/App');
    createRoot(document.getElementById('root')!).render(
      <StrictMode>
        <App />
      </StrictMode>,
    );
  })
  .catch((error) => {
    console.error('[Keycloak] Init failed:', error);
    const errorMessage = error instanceof Error ? error.message : String(error);
    createRoot(document.getElementById('root')!).render(
      <div style={{ padding: '2rem', textAlign: 'center' }}>
        <h1>Unable to connect to authentication server</h1>
        <p>Please check that Keycloak is running and try again.</p>
        <p style={{ color: '#888', fontSize: '0.875rem', fontFamily: 'monospace' }}>{errorMessage}</p>
        <button onClick={() => window.location.reload()}>Retry</button>
      </div>,
    );
  });
