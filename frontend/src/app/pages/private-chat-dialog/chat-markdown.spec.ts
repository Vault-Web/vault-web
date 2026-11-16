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

  it('opens sanitized links in a new tab with no opener or referrer', () => {
    const container = document.createElement('div');
    container.innerHTML = renderChatMarkdown(
      '[safe](https://example.com)\n\n<a href="https://example.org" target="named" rel="opener" onclick="alert(1)">raw</a>',
    );

    const links = container.querySelectorAll('a');
    expect(links.length).toBe(2);
    expect(links[0].getAttribute('href')).toBe('https://example.com');
    expect(links[1].getAttribute('href')).toBe('https://example.org');
    links.forEach((link) => {
      expect(link.getAttribute('target')).toBe('_blank');
      expect(link.getAttribute('rel')).toBe('noopener noreferrer');
      expect(link.hasAttribute('onclick')).toBeFalse();
    });
  });
});
