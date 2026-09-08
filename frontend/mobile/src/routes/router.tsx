import { createBrowserRouter } from 'react-router'
import { AuthGuard } from '@/auth/auth-guard'
import { Login } from '@/screens/login'
import { InboxHome } from '@/screens/inbox-home'
import { PickExecution } from '@/screens/pick-execution'
import { MoveExecution } from '@/screens/move-execution'
import { CountExecution } from '@/screens/count-execution'
import { ReceiveExecution } from '@/screens/receive-execution'
import { SyncIssues } from '@/screens/sync-issues'
import { MenuScreen } from '@/screens/menu-screen'
import { InquiryScreen } from '@/screens/inquiry-screen'
import { AdhocMoveScreen } from '@/screens/adhoc-move-screen'
import { ReceiveSelectScreen } from '@/screens/receive-select-screen'
import { AdhocCountScreen } from '@/screens/adhoc-count-screen'
import { PackScreen } from '@/screens/pack-screen'
import { ReprintScreen } from '@/screens/reprint-screen'
import { SortScreen } from '@/screens/sort-screen'
import { PackoutScreen } from '@/screens/packout-screen'

export const router = createBrowserRouter([
  { path: '/login', element: <Login /> },
  { element: <AuthGuard />, children: [
    { path: '/', element: <InboxHome /> },
    { path: '/pick/:ref', element: <PickExecution /> },
    { path: '/move/:ref', element: <MoveExecution /> },
    { path: '/count/:ref', element: <CountExecution /> },
    { path: '/receive/:ref', element: <ReceiveExecution /> },
    { path: '/sync-issues', element: <SyncIssues /> },
    { path: '/menu', element: <MenuScreen /> },
    { path: '/inquiry', element: <InquiryScreen /> },
    { path: '/adhoc-move', element: <AdhocMoveScreen /> },
    { path: '/receive-select', element: <ReceiveSelectScreen /> },
    { path: '/adhoc-count', element: <AdhocCountScreen /> },
    { path: '/pack', element: <PackScreen /> },
    { path: '/reprint', element: <ReprintScreen /> },
    { path: '/sort', element: <SortScreen /> },
    { path: '/packout', element: <PackoutScreen /> },
  ] },
], { basename: '/m' })
