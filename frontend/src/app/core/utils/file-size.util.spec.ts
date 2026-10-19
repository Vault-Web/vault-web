import { formatFileSize } from './file-size.util';

describe('formatFileSize', () => {
  const cases: [number, string][] = [
    [0, '0 Bytes'],
    [1, '1 Bytes'],
    [500, '500 Bytes'],
    [999, '999 Bytes'],
    [1000, '1 KB'],
    [1024, '1.02 KB'],
    [1234, '1.23 KB'],
    [1e6 - 1000, '999 KB'],
    [1e6, '1 MB'],
    [1048576, '1.05 MB'],
    [1e9 - 1e6, '999 MB'],
    [1e9, '1 GB'],
    [1e12 - 1e9, '999 GB'],
    [1e12, '1 TB'],
    [1e15 - 1e12, '999 TB'],
    [1e15, '1 PB'],
    [1e18, '1000 PB'],
  ];

  for (const [bytes, expected] of cases) {
    it(`formats ${bytes} bytes as ${expected}`, () => {
      expect(formatFileSize(bytes)).toBe(expected);
    });
  }

  for (const bytes of [-1, NaN, Infinity, -Infinity]) {
    it(`hides invalid size ${bytes}`, () => {
      expect(formatFileSize(bytes)).toBe('');
    });
  }
});
