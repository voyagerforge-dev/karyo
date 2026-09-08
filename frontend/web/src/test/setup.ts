import '@testing-library/jest-dom/vitest';

// jsdom doesn't implement the Pointer Events capture APIs or scrollIntoView --
// Radix UI primitives (Select, etc.) call these unconditionally, so without a
// stub any test that opens a Radix popover throws "not a function".
if (!Element.prototype.hasPointerCapture) {
  Element.prototype.hasPointerCapture = () => false;
}
if (!Element.prototype.setPointerCapture) {
  Element.prototype.setPointerCapture = () => {};
}
if (!Element.prototype.releasePointerCapture) {
  Element.prototype.releasePointerCapture = () => {};
}
if (!Element.prototype.scrollIntoView) {
  Element.prototype.scrollIntoView = () => {};
}
