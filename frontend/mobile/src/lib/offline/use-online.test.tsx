import { render, screen, act } from '@testing-library/react'
import { useOnline } from '@/lib/offline/use-online'

function setOnline(v: boolean) { Object.defineProperty(navigator, 'onLine', { configurable: true, value: v }) }
function Probe() { return <span data-testid="s">{useOnline() ? 'on' : 'off'}</span> }
afterEach(() => setOnline(true))

test('reflects navigator.onLine and updates on offline/online events', () => {
  setOnline(true)
  render(<Probe />)
  expect(screen.getByTestId('s').textContent).toBe('on')
  act(() => { setOnline(false); window.dispatchEvent(new Event('offline')) })
  expect(screen.getByTestId('s').textContent).toBe('off')
  act(() => { setOnline(true); window.dispatchEvent(new Event('online')) })
  expect(screen.getByTestId('s').textContent).toBe('on')
})
