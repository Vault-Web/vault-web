import DOMPurify from 'dompurify';
import { marked } from 'marked';

const MARKDOWN_STYLES = `
.chat-bubble .chat-markdown { line-height: 1.5; }
.chat-bubble .chat-markdown p { margin: 0.25rem 0; }
.chat-bubble .chat-markdown p:first-child { margin-top: 0; }
.chat-bubble .chat-markdown p:last-child { margin-bottom: 0; }
.chat-bubble .chat-markdown ul, .chat-bubble .chat-markdown ol { margin: 0.35rem 0; padding-left: 1.25rem; }
.chat-bubble .chat-markdown ul { list-style: disc; }
.chat-bubble .chat-markdown ol { list-style: decimal; }
.chat-bubble .chat-markdown li { margin: 0.15rem 0; }
.chat-bubble .chat-markdown blockquote { margin: 0.5rem 0; padding-left: 0.75rem; border-left: 3px solid currentColor; opacity: 0.85; }
.chat-bubble .chat-markdown code { font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 0.9em; padding: 0.1rem 0.3rem; border-radius: 0.25rem; background: color-mix(in srgb, currentColor 10%, transparent); }
.chat-bubble .chat-markdown pre { margin: 0.5rem 0; padding: 0.65rem; overflow-x: auto; border-radius: 0.5rem; background: color-mix(in srgb, currentColor 10%, transparent); }
.chat-bubble .chat-markdown pre code { padding: 0; background: transparent; }
.chat-bubble .chat-markdown a { text-decoration: underline; overflow-wrap: anywhere; }
`;

let stylesInstalled = false;

function installStyles(): void {
  if (stylesInstalled || typeof document === 'undefined') return;
  const style = document.createElement('style');
  style.setAttribute('data-chat-markdown', 'true');
  style.textContent = MARKDOWN_STYLES;
  document.head.appendChild(style);
  stylesInstalled = true;
}

export function renderChatMarkdown(content: string): string {
  installStyles();
  const rendered = marked.parse(content ?? '', {
    breaks: true,
    async: false,
  }) as string;
  const withSafeLinkAttributes = rendered.replace(
    /<a\b(?![^>]*\btarget=)([^>]*)>/gi,
    '<a target="_blank" rel="noopener noreferrer"$1>',
  );
  const cleanHtml = DOMPurify.sanitize(withSafeLinkAttributes);
  return `<div class="chat-markdown">${cleanHtml}</div>`;
}
