export interface InvoiceLine {
  description: string
  quantity: number
  price: string
}

export interface Invoice {
  id: string
  currency: string
  lines: InvoiceLine[]
}

export function lineCents(line: InvoiceLine): number {
  return Math.round(Number(line.price) * 100) * line.quantity
}

export function totalCents(invoice: Invoice): number {
  return invoice.lines.reduce((sum, line) => sum + Math.round(parseFloat(line.price) * 100) * line.quantity, 0)
}
