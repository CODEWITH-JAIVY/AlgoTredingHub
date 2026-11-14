export function formatIndicator(value: string | number | null | undefined, digits = 2): string {
  if (value === null || value === undefined) return "—";
  if (typeof value === "string" && value.trim() === "") return "—";
  const parsed = typeof value === "number" ? value : Number(value.trim());
  if (!Number.isFinite(parsed)) return "—";
  const precision = Number.isInteger(digits) ? Math.max(0, Math.min(6, digits)) : 2;
  return new Intl.NumberFormat("en-IN", { maximumFractionDigits: precision }).format(parsed);
}
