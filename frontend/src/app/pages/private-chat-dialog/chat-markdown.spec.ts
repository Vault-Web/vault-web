import { renderChatMarkdown } from './chat-markdown';

describe('renderChatMarkdown', () => {
  it('renders common Markdown formatting', () => {
    const html = renderChatMarkdown(
      '**bold**\n\n- one\n- two\n\n> quote\n\n`inline`\n\n```ts\nconst value = 1;\n```',
    );

    expect(html).toContain('<strong>bold</strong>');
    expect(html).toContain('<ul>');
    expect(html).toContain('<blockquote>');
    expect(html).toContain('<code>inline</code>');
    expect(html).toContain('<pre>');
    expect(html).toContain('const value = 1;');
  });

  it('sanitizes HTML and dangerous links after Markdown parsing', () => {
    const html = renderChatMarkdown(
      '<img src=x onerror="alert(1)">\n\n[bad](javascript:alert(1))',
    );

    expect(html).not.toContain('onerror');
    expect(html).not.toContain('javascript:');
  });
});
