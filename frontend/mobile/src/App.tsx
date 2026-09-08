import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import { RouterProvider } from 'react-router'
import { Toaster } from 'sonner'
import { AuthProvider } from '@/auth/auth-provider'
import { router } from '@/routes/router'
import { OfflineBanner } from '@/components/hmi/offline-banner'

const queryClient = new QueryClient({ defaultOptions: { queries: { retry: 1, staleTime: 10_000, refetchOnWindowFocus: false } } })

export default function App() {
  return (
    <AuthProvider>
      <QueryClientProvider client={queryClient}>
        <OfflineBanner />
        <RouterProvider router={router} />
        <Toaster position="top-center" richColors />
      </QueryClientProvider>
    </AuthProvider>
  )
}
