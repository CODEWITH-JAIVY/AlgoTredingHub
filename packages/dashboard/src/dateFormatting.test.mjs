import assert from "node:assert/strict";
import test from "node:test";
import { formatDateTimeIst } from "./dateFormatting.ts";

test("order timestamps render in IST independently of the host timezone", () => {
  const originalTimezone = process.env.TZ;
  try {
    process.env.TZ = "Pacific/Honolulu";
    const west = formatDateTimeIst("2026-10-21T04:30:00.000Z", "en-GB");
    process.env.TZ = "Europe/London";
    const london = formatDateTimeIst("2026-10-21T04:30:00.000Z", "en-GB");
    assert.equal(west, "21 Oct 2026, 10:00");
    assert.equal(london, west);
  } finally {
    if (originalTimezone === undefined) delete process.env.TZ;
    else process.env.TZ = originalTimezone;
  }
});

test("invalid timestamps render as unavailable instead of throwing", () => {
  assert.equal(formatDateTimeIst("not-a-timestamp", "en-IN"), "—");
});
