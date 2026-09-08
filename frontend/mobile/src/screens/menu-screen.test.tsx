import { describe, expect, it, vi } from 'vitest'
import { render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'

vi.mock('@/menu/use-menu', () => ({
  useMenu: () => ({
    items: [
      { id: 'inquiry', num: 1, label: 'Inquiry', route: '/inquiry', roles: [] },
      { id: 'move', num: 2, label: 'Move', route: '/adhoc-move', roles: [] },
    ],
    online: false,
  }),
}))

import { MenuScreen } from '@/screens/menu-screen'

describe('MenuScreen', () => {
  it('renders numbered items and offline badges when disconnected', () => {
    render(<MemoryRouter><MenuScreen /></MemoryRouter>)
    expect(screen.getByText('1')).toBeInTheDocument()
    expect(screen.getByText('Inquiry')).toBeInTheDocument()
    expect(screen.getAllByText('OFFLINE')).toHaveLength(2)
  })
})
