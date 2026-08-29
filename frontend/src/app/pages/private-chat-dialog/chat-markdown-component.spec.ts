import { DomSanitizer } from '@angular/platform-browser';
import { PrivateChatDialogComponent } from './private-chat-dialog.component';

describe('PrivateChatDialogComponent Markdown formatting', () => {
  it('renders Markdown through formatMessage and preserves the SafeHtml contract', () => {
    const sanitizer = jasmine.createSpyObj<DomSanitizer>('DomSanitizer', [
      'bypassSecurityTrustHtml',
    ]);
    const trustedHtml = {};
    sanitizer.bypassSecurityTrustHtml.and.returnValue(trustedHtml as any);

    const component = new PrivateChatDialogComponent(
      {} as any,
      {} as any,
      {} as any,
      {} as any,
      {} as any,
      {} as any,
      {} as any,
      sanitizer,
    );

    const result = component.formatMessage('**bold**');

    expect(result).toBe(trustedHtml as any);
    expect(sanitizer.bypassSecurityTrustHtml).toHaveBeenCalledTimes(1);
    expect(sanitizer.bypassSecurityTrustHtml).toHaveBeenCalledWith(
      jasmine.stringContaining('<strong>bold</strong>'),
    );
  });

  it('reuses the formatted message cache for repeated content', () => {
    const sanitizer = jasmine.createSpyObj<DomSanitizer>('DomSanitizer', [
      'bypassSecurityTrustHtml',
    ]);
    const trustedHtml = {};
    sanitizer.bypassSecurityTrustHtml.and.returnValue(trustedHtml as any);

    const component = new PrivateChatDialogComponent(
      {} as any,
      {} as any,
      {} as any,
      {} as any,
      {} as any,
      {} as any,
      {} as any,
      sanitizer,
    );

    const first = component.formatMessage('**bold**');
    const second = component.formatMessage('**bold**');

    expect(second).toBe(first);
    expect(sanitizer.bypassSecurityTrustHtml).toHaveBeenCalledTimes(1);
  });
});
