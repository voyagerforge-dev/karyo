import { describe, expect, it, vi } from 'vitest'
import { render, screen, fireEvent } from '@testing-library/react'
import { FreeScanField } from '@/components/hmi/free-scan-field'

vi.mock('@/hooks/use-scanner', () => ({ useScanner: vi.fn(), vibrate: vi.fn() }))

describe('FreeScanField', () => {
  it('manual entry submits normalized value', () => {
    const onScan = vi.fn()
    render(<FreeScanField label="Scan anything" onScan={onScan} />)
    const input = screen.getByLabelText('Scan anything')
    fireEvent.change(input, { target: { value: '  ul-000123 ' } })
    fireEvent.submit(input.closest('form')!)
    expect(onScan).toHaveBeenCalledWith('UL-000123')
  })
})
