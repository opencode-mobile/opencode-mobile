const SYMBOLS: Record<string, string> = { USD: "$", EUR: "€", GBP: "£" }

export function currencySymbol(currency: string): string {
  return SYMBOLS[currency] ?? `${currency} `
}

export function formatMoney(cents: number, currency: string): string {
  const symbol = currencySymbol(currency)
  return `${symbol}${(cents / 100).toFixed(2)}`
}
