function sanitizeCsvCell(value: unknown) {
  let text = value == null ? "" : String(value);
  if (/^[\u0000-\u0020]*[=+\-@]/.test(text)) {
    text = `'${text}`;
  }
  return `"${text.replace(/"/g, '""')}"`;
}

export function serializeCsv(headers: string[], rows: Array<Array<unknown>>) {
  return [headers, ...rows]
    .map((row) => row.map(sanitizeCsvCell).join(","))
    .join("\r\n");
}

export function downloadCsv(
  filename: string,
  headers: string[],
  rows: Array<Array<unknown>>,
) {
  const blob = new Blob(["\uFEFF", serializeCsv(headers, rows)], {
    type: "text/csv;charset=utf-8",
  });
  const url = URL.createObjectURL(blob);
  const anchor = document.createElement("a");
  anchor.href = url;
  anchor.download = filename;
  anchor.hidden = true;
  document.body.appendChild(anchor);
  anchor.click();
  anchor.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 0);
}
