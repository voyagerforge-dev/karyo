/** Status tone → rail / dot color (Control palette). Shared by master-detail. */
export type EntityTone = 'lime' | 'blue' | 'amber' | 'red' | 'violet' | 'grey';

export const TONE_COLOR: Record<EntityTone, string> = {
  lime: '#C7F24E',
  blue: '#5AB7E0',
  amber: '#F0B43C',
  red: '#FF6A45',
  violet: '#7C6CCF',
  grey: '#6E6655',
};
