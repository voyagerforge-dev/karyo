import type { SelectionCondition, SelectionFieldResponse, SelectionRule } from '@/types/waves';

/**
 * Client-side helpers for the RULES tab's rule builder (selection-rules sprint, Task 5).
 * Mirrors `com.karyo.wave.rule.RuleValidator`'s structural caps -- this is a UX guardrail
 * only, the server (`RuleValidator`) is the source of truth and re-validates on save.
 */

/** Mirrors `RuleValidator.MAX_CONDITIONS`. */
export const MAX_CONDITIONS = 20;

/** Mirrors `RuleValidator.MAX_DEPTH` -- a top-level rule plus one level of nested groups. */
export const MAX_DEPTH = 2;

/** Mirrors `RuleValidator`'s `NO_VALUE_OPS`. */
const NO_VALUE_OPS = new Set(['isNull', 'notNull']);

export function emptyCondition(field: string): SelectionCondition {
  return { field, op: '', value: undefined };
}

export function emptyRule(): SelectionRule {
  return { combinator: 'AND', conditions: [], groups: [] };
}

export function opRequiresNoValue(op: string): boolean {
  return NO_VALUE_OPS.has(op);
}

export function opRequiresArray(op: string): boolean {
  return op === 'in';
}

export function opRequiresRange(op: string): boolean {
  return op === 'between';
}

/** Total condition count across the rule and every nested group -- mirrors
 *  `RuleValidator.countConditions`, the number the 20-condition cap is checked against. */
export function countConditions(rule: SelectionRule): number {
  return rule.conditions.length + rule.groups.reduce((sum, g) => sum + countConditions(g), 0);
}

function isFilledScalar(value: unknown): boolean {
  return value !== undefined && value !== null && value !== '';
}

/** A condition is "complete enough to save" when it has a field, an op legal for that field
 *  (registry-driven, not hardcoded), and -- unless the op needs no value -- a filled-in value
 *  of the right shape. This only catches the obviously-incomplete case; the server's
 *  `RuleValidator` is the authority on type/shape correctness. */
export function isConditionComplete(
  condition: SelectionCondition,
  fields: readonly SelectionFieldResponse[],
): boolean {
  const field = fields.find((f) => f.field === condition.field);
  if (!field || !condition.op || !field.ops.includes(condition.op)) return false;
  if (opRequiresNoValue(condition.op)) return true;

  const value = condition.value;
  if (opRequiresRange(condition.op)) {
    return Array.isArray(value) && value.length === 2 && value.every(isFilledScalar);
  }
  if (opRequiresArray(condition.op)) {
    return Array.isArray(value) && value.length > 0 && value.every(isFilledScalar);
  }
  return isFilledScalar(value);
}

/** True when every condition in the rule (top level + nested groups) is complete, and no nested
 *  group is itself empty (mirrors `RuleValidator`'s IMPORTANT-2 fix: an empty nested group
 *  matches everything, so `OR(cond, group[])` would sweep the whole pool). The top-level rule's
 *  own empty case is caught separately by `total === 0` in `RuleEditor.handleSave`. */
export function allConditionsComplete(
  rule: SelectionRule,
  fields: readonly SelectionFieldResponse[],
): boolean {
  return (
    rule.conditions.every((c) => isConditionComplete(c, fields)) &&
    rule.groups.every(
      (g) => (g.conditions.length > 0 || g.groups.length > 0) && allConditionsComplete(g, fields),
    )
  );
}

/** TODAY-relative literal grammar shared with the backend `TodayLiteral` -- offset capped at
 *  4 digits (`+/-9999`), matching the server grammar's own cap. */
const RELATIVE_RE = /^TODAY([+-]\d{1,4})$/;

/** Parses a "TODAY+N"/"TODAY-N" literal into its numeric offset, or null if `value` isn't one. */
export function parseRelativeOffset(value: unknown): number | null {
  if (typeof value !== 'string') return null;
  const m = RELATIVE_RE.exec(value);
  return m ? Number(m[1]) : null;
}

/** Formats a numeric day offset as a "TODAY+N"/"TODAY-N" literal, capped at the 4-digit grammar. */
export function formatRelativeOffset(offset: number): string {
  const capped = Math.max(-9999, Math.min(9999, Math.trunc(offset || 0)));
  return capped >= 0 ? `TODAY+${capped}` : `TODAY${capped}`;
}

/**
 * DATETIME conditions never carry a browser `<input type="datetime-local">` value directly --
 * that control emits a bare local wall-clock string ("2026-08-21T00:00", no offset), which
 * `TodayLiteral.parseInstant` (server-side) can never parse: it accepts a TODAY literal, a
 * strict `Instant` ("...Z"/offset), or a plain ISO date, never an offset-less local datetime.
 * Every condition on a DATETIME field would 422 regardless of what the operator picked. The
 * honest interpretation of a datetime picker is "this moment, in the operator's own timezone" --
 * so the picker's local value is converted to a strict UTC instant before it ever reaches
 * `SelectionCondition.value`, and converted back for display when editing a saved rule.
 */

/** Converts a datetime-local input's raw value into a strict Instant string ("...Z"). Empty or
 *  unparseable input returns '' (an in-progress typing state, not yet a condition value). */
export function datetimeLocalToInstant(local: string): string {
  if (!local) return '';
  const d = new Date(local);
  return Number.isNaN(d.getTime()) ? '' : d.toISOString();
}

/** Converts a strict Instant string back into the datetime-local display format
 *  (`yyyy-MM-ddTHH:mm`, local time) for editing a saved DATETIME condition. Returns '' if
 *  `value` isn't a parseable instant (e.g. still a relative literal, or malformed). */
export function instantToDatetimeLocal(value: unknown): string {
  if (typeof value !== 'string' || value === '') return '';
  const d = new Date(value);
  if (Number.isNaN(d.getTime())) return '';
  const pad = (n: number) => String(n).padStart(2, '0');
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}
