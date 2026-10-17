import { test } from "node:test"
import assert from "node:assert/strict"
import { totalCents } from "../src/invoice.ts"

test("sums line items in cents", () => {
  const invoice = { id: "inv_1", currency: "USD", lines: [{ description: "Widget", quantity: 2, price: "1.25" }] }
  assert.equal(totalCents(invoice), 250)
})
