import { test } from "node:test"
import assert from "node:assert/strict"
import { formatMoney } from "../src/format.ts"

test("formats whole dollars", () => {
  assert.equal(formatMoney(1200, "USD"), "$12.00")
})
