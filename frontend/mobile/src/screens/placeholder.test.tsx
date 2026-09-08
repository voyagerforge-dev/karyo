import { render, screen } from '@testing-library/react'
import { Placeholder } from '@/screens/placeholder'

test('renders the floor heading', () => {
  render(<Placeholder />)
  expect(screen.getByText('KARYO FLOOR')).toBeInTheDocument()
})
