/** Format decimal byte sizes consistently across Cloud, Trash, and public shares. */
export function formatFileSize(bytes: number): string {
  if (!Number.isFinite(bytes) || bytes < 0) {
    return '';
  }

  const units = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB'];
  let value = bytes;
  let unit = 0;
  while (value >= 1000 && unit < units.length - 1) {
    value /= 1000;
    unit++;
  }

  return `${parseFloat(value.toFixed(2))} ${units[unit]}`;
}
