import { render } from '@testing-library/react'
import { useScanner } from '@/hooks/use-scanner'

function Harness({ onScan }: { onScan: (c: string) => void }) { useScanner(onScan); return null }

function typeWedge(text: string) {
  for (const ch of text) window.dispatchEvent(new KeyboardEvent('keydown', { key: ch }))
  window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
}

test('accumulates rapid keys ending in Enter into one scan', () => {
  const onScan = vi.fn()
  render(<Harness onScan={onScan} />)
  typeWedge('A-01-02')
  expect(onScan).toHaveBeenCalledWith('A-01-02')
})

test('Enter with empty buffer does not fire', () => {
  const onScan = vi.fn()
  render(<Harness onScan={onScan} />)
  window.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter' }))
  expect(onScan).not.toHaveBeenCalled()
})
