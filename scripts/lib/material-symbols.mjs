const SYMBOLS_BASE_URL =
  "https://raw.githubusercontent.com/google/material-design-icons/master/symbols/web";

export function materialSymbolsSvgUrl(iconName) {
  return `${SYMBOLS_BASE_URL}/${iconName}/materialsymbolsoutlined/${iconName}_24px.svg`;
}

export async function fetchMaterialSymbolSvg(iconName) {
  const url = materialSymbolsSvgUrl(iconName);
  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`Failed to download Material Symbol ${iconName} from ${url} (${response.status})`);
  }
  return response.text();
}
