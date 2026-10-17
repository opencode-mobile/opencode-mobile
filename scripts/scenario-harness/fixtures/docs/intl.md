# Formatting currency with Intl

`Intl.NumberFormat` formats money for a locale:

```js
new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(10.05)
// "$10.05"
```

- Pass `currencyDisplay: "narrowSymbol"` to print `$` instead of `US$` outside the US.
- JPY has 0 minor units; `Intl` handles that when you pass `currency: "JPY"`.
