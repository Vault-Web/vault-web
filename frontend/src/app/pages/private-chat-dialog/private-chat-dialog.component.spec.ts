/// <reference types="jasmine" />

import {
  ComponentFixture,
  TestBed,
  discardPeriodicTasks,
  fakeAsync,
  tick,
} from '@angular/core/testing';
import { of, Subject } from 'rxjs';
import { UiToastService } from '../../core/services/ui-toast.service';
import { ChatMessageDto } from '../../models/dtos/ChatMessageDto';
import { TypingIndicatorDto } from '../../models/dtos/TypingIndicatorDto';
import { DeviceDto } from '../../models/dtos/DeviceDto';
import { E2eeService } from '../../services/e2ee.service';
import { GroupChatService } from '../../services/group-chat.service';
import { PrivateChatService } from '../../services/private-chat.service';
import { WebSocketService } from '../../services/web-socket.service';
import { PrivateChatDialogComponent } from './private-chat-dialog.component';
import { GroupService } from '../../services/group.service';
import { UserService } from '../../services/user.service';

describe('PrivateChatDialogComponent typing indicators', () => {
  let fixture: ComponentFixture<PrivateChatDialogComponent>;
  let component: PrivateChatDialogComponent;
  let typingEvents: Subject<TypingIndicatorDto>;
  let wsService: jasmine.SpyObj<WebSocketService>;

  beforeEach(async () => {
    typingEvents = new Subject<TypingIndicatorDto>();
    wsService = jasmine.createSpyObj<WebSocketService>('WebSocketService', [
      'subscribeToPrivateMessages',
      'subscribeToTypingIndicators',
      'sendTypingIndicator',
      'ensureConnected',
      'sendPrivateMessage',
      'sendGroupMessage',
      'subscribeToGroupMessages',
    ]);
    wsService.subscribeToPrivateMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToGroupMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToTypingIndicators.and.returnValue(
      typingEvents.asObservable(),
    );
    wsService.sendTypingIndicator.and.returnValue(true);
    wsService.ensureConnected.and.resolveTo(true);
    wsService.sendPrivateMessage.and.returnValue(true);
    wsService.sendGroupMessage.and.returnValue(true);

    const chatService = jasmine.createSpyObj<PrivateChatService>(
      'PrivateChatService',
      ['getMessages', 'getDevices'],
    );
    chatService.getMessages.and.returnValue(of([]));
    chatService.getDevices.and.returnValue(of<DeviceDto[]>([]));

    const groupChatService = jasmine.createSpyObj<GroupChatService>(
      'GroupChatService',
      ['getMessages', 'getDevices'],
    );
    groupChatService.getMessages.and.returnValue(of([]));
    groupChatService.getDevices.and.returnValue(of<DeviceDto[]>([]));

    const e2eeService = jasmine.createSpyObj<E2eeService>('E2eeService', [
      'ensureDeviceRegistered',
      'encryptForDevices',
      'decryptPayload',
    ]);
    e2eeService.ensureDeviceRegistered.and.resolveTo();

    const toast = jasmine.createSpyObj<UiToastService>('UiToastService', [
      'error',
      'warn',
    ]);

    const groupService = jasmine.createSpyObj<GroupService>('GroupService', [
      'getGroupDetails',
    ]);
    const userService = jasmine.createSpyObj<UserService>('UserService', [
      'getProfilePictureUrl',
    ]);

    await TestBed.configureTestingModule({
      imports: [PrivateChatDialogComponent],
      providers: [
        { provide: WebSocketService, useValue: wsService },
        { provide: PrivateChatService, useValue: chatService },
        { provide: GroupChatService, useValue: groupChatService },
        { provide: E2eeService, useValue: e2eeService },
        { provide: UiToastService, useValue: toast },
        { provide: GroupService, useValue: groupService },
        { provide: UserService, useValue: userService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PrivateChatDialogComponent);
    component = fixture.componentInstance;
    component.username = 'bob';
    component.currentUsername = 'alice';
    component.privateChatId = 10;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
  });

  it('emits one typing start while active and a stop after local idle timeout', fakeAsync(() => {
    component.onComposerInput('h');
    component.onComposerInput('he');
    component.onComposerInput('hel');

    expect(wsService.sendTypingIndicator).toHaveBeenCalledTimes(1);
    expect(wsService.sendTypingIndicator).toHaveBeenCalledWith({
      type: 'typing_start',
      privateChatId: 10,
      groupId: null,
    });

    tick(5000);

    expect(wsService.sendTypingIndicator).toHaveBeenCalledTimes(2);
    expect(wsService.sendTypingIndicator).toHaveBeenCalledWith({
      type: 'typing_stop',
      privateChatId: 10,
      groupId: null,
    });
    discardPeriodicTasks();
  }));

  it('shows and clears incoming typing indicators for the active private chat', fakeAsync(() => {
    typingEvents.next({
      type: 'typing_start',
      privateChatId: 10,
      username: 'bob',
    });

    expect(component.typingIndicatorLabel).toBe('bob is typing...');

    typingEvents.next({
      type: 'typing_stop',
      privateChatId: 10,
      username: 'bob',
    });

    expect(component.typingIndicatorLabel).toBe('');
    discardPeriodicTasks();
  }));
});

describe('PrivateChatDialogComponent formatMessage', () => {
  let fixture: ComponentFixture<PrivateChatDialogComponent>;
  let component: PrivateChatDialogComponent;

  beforeEach(async () => {
    const typingEvents = new Subject<TypingIndicatorDto>();
    const wsService = jasmine.createSpyObj<WebSocketService>(
      'WebSocketService',
      [
        'subscribeToPrivateMessages',
        'subscribeToTypingIndicators',
        'sendTypingIndicator',
        'ensureConnected',
        'sendPrivateMessage',
        'sendGroupMessage',
        'subscribeToGroupMessages',
      ],
    );
    wsService.subscribeToPrivateMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToGroupMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToTypingIndicators.and.returnValue(
      typingEvents.asObservable(),
    );
    wsService.sendTypingIndicator.and.returnValue(true);
    wsService.ensureConnected.and.resolveTo(true);
    wsService.sendPrivateMessage.and.returnValue(true);
    wsService.sendGroupMessage.and.returnValue(true);

    const chatService = jasmine.createSpyObj<PrivateChatService>(
      'PrivateChatService',
      ['getMessages', 'getDevices'],
    );
    chatService.getMessages.and.returnValue(of([]));
    chatService.getDevices.and.returnValue(of<DeviceDto[]>([]));

    const groupChatService = jasmine.createSpyObj<GroupChatService>(
      'GroupChatService',
      ['getMessages', 'getDevices'],
    );
    groupChatService.getMessages.and.returnValue(of([]));
    groupChatService.getDevices.and.returnValue(of<DeviceDto[]>([]));

    const e2eeService = jasmine.createSpyObj<E2eeService>('E2eeService', [
      'ensureDeviceRegistered',
      'encryptForDevices',
      'decryptPayload',
    ]);
    e2eeService.ensureDeviceRegistered.and.resolveTo();

    const toast = jasmine.createSpyObj<UiToastService>('UiToastService', [
      'error',
      'warn',
    ]);

    const groupService = jasmine.createSpyObj<GroupService>('GroupService', [
      'getGroupDetails',
    ]);
    const userService = jasmine.createSpyObj<UserService>('UserService', [
      'getProfilePictureUrl',
    ]);

    await TestBed.configureTestingModule({
      imports: [PrivateChatDialogComponent],
      providers: [
        { provide: WebSocketService, useValue: wsService },
        { provide: PrivateChatService, useValue: chatService },
        { provide: GroupChatService, useValue: groupChatService },
        { provide: E2eeService, useValue: e2eeService },
        { provide: UiToastService, useValue: toast },
        { provide: GroupService, useValue: groupService },
        { provide: UserService, useValue: userService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PrivateChatDialogComponent);
    component = fixture.componentInstance;
    component.username = 'bob';
    component.currentUsername = 'alice';
    component.privateChatId = 10;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
  });

  /** Helper: extract the raw HTML string from SafeHtml. */
  function toHtml(safeHtml: unknown): string {
    // SafeHtml wraps the value; coerce to string via a temp element
    const div = document.createElement('div');
    div.innerHTML =
      (safeHtml as any)?.changingThisBreaksApplicationSecurity ??
      String(safeHtml);
    return div.innerHTML;
  }

  it('returns empty string for undefined input', () => {
    expect(component.formatMessage(undefined) as string).toBe('');
  });

  it('returns empty string for empty string input', () => {
    expect(component.formatMessage('') as string).toBe('');
  });

  it('renders plain text without any link markup', () => {
    const html = toHtml(component.formatMessage('hello world'));
    expect(html).not.toContain('<a');
    expect(html).toContain('hello world');
  });

  it('converts a single URL into a clickable link', () => {
    const html = toHtml(component.formatMessage('https://example.com'));
    expect(html).toContain('<a');
    expect(html).toContain('href="https://example.com"');
    expect(html).toContain('target="_blank"');
    expect(html).toContain('rel="noopener noreferrer"');
  });

  it('keeps surrounding text as plain text', () => {
    const html = toHtml(
      component.formatMessage('visit https://example.com now'),
    );
    expect(html).toContain('visit ');
    expect(html).toContain(' now');
    expect(html).toContain('href="https://example.com"');
  });

  it('handles multiple URLs in one message', () => {
    const html = toHtml(
      component.formatMessage('see https://a.com and http://b.com'),
    );
    const anchors = html.match(/<a /g);
    expect(anchors?.length).toBe(2);
  });

  it('strips trailing punctuation from URLs', () => {
    const html = toHtml(component.formatMessage('go to https://example.com.'));
    expect(html).toContain('href="https://example.com"');
    // The trailing period should be outside the anchor
    expect(html).not.toContain('href="https://example.com."');
  });

  it('escapes HTML entities in plain text to prevent XSS', () => {
    const html = toHtml(
      component.formatMessage('<script>alert("xss")</script>'),
    );
    expect(html).not.toContain('<script>');
    expect(html).toContain('&lt;script&gt;');
  });

  it('does not linkify javascript: scheme URLs', () => {
    const html = toHtml(component.formatMessage('javascript:alert(1)'));
    expect(html).not.toContain('<a');
  });
});

describe('PrivateChatDialogComponent duplicate message detection', () => {
  let fixture: ComponentFixture<PrivateChatDialogComponent>;
  let component: PrivateChatDialogComponent;
  let privateMessages: Subject<ChatMessageDto>;
  let groupMessages: Subject<ChatMessageDto>;
  let wsService: jasmine.SpyObj<WebSocketService>;

  beforeEach(async () => {
    privateMessages = new Subject<ChatMessageDto>();
    groupMessages = new Subject<ChatMessageDto>();
    wsService = jasmine.createSpyObj<WebSocketService>('WebSocketService', [
      'subscribeToPrivateMessages',
      'subscribeToTypingIndicators',
      'sendTypingIndicator',
      'ensureConnected',
      'sendPrivateMessage',
      'sendGroupMessage',
      'subscribeToGroupMessages',
    ]);
    wsService.subscribeToPrivateMessages.and.returnValue(
      privateMessages.asObservable(),
    );
    wsService.subscribeToGroupMessages.and.returnValue(
      groupMessages.asObservable(),
    );
    wsService.subscribeToTypingIndicators.and.returnValue(
      new Subject<TypingIndicatorDto>().asObservable(),
    );
    wsService.sendTypingIndicator.and.returnValue(true);
    wsService.ensureConnected.and.resolveTo(true);
    wsService.sendPrivateMessage.and.returnValue(true);
    wsService.sendGroupMessage.and.returnValue(true);

    const chatService = jasmine.createSpyObj<PrivateChatService>(
      'PrivateChatService',
      ['getMessages', 'getDevices'],
    );
    chatService.getMessages.and.returnValue(of([]));
    chatService.getDevices.and.returnValue(of<DeviceDto[]>([]));

    const groupChatService = jasmine.createSpyObj<GroupChatService>(
      'GroupChatService',
      ['getMessages', 'getDevices'],
    );
    groupChatService.getMessages.and.returnValue(of([]));
    groupChatService.getDevices.and.returnValue(of<DeviceDto[]>([]));

    const e2eeService = jasmine.createSpyObj<E2eeService>('E2eeService', [
      'ensureDeviceRegistered',
      'encryptForDevices',
      'decryptPayload',
    ]);
    e2eeService.ensureDeviceRegistered.and.resolveTo();

    const toast = jasmine.createSpyObj<UiToastService>('UiToastService', [
      'error',
      'warn',
    ]);

    const groupService = jasmine.createSpyObj<GroupService>('GroupService', [
      'getGroupDetails',
    ]);
    const userService = jasmine.createSpyObj<UserService>('UserService', [
      'getProfilePictureUrl',
    ]);

    await TestBed.configureTestingModule({
      imports: [PrivateChatDialogComponent],
      providers: [
        { provide: WebSocketService, useValue: wsService },
        { provide: PrivateChatService, useValue: chatService },
        { provide: GroupChatService, useValue: groupChatService },
        { provide: E2eeService, useValue: e2eeService },
        { provide: UiToastService, useValue: toast },
        { provide: GroupService, useValue: groupService },
        { provide: UserService, useValue: userService },
      ],
    }).compileComponents();

    fixture = TestBed.createComponent(PrivateChatDialogComponent);
    component = fixture.componentInstance;
    component.username = 'bob';
    component.currentUsername = 'alice';
    component.privateChatId = 10;
    fixture.detectChanges();
  });

  afterEach(() => {
    fixture.destroy();
  });

  function baseMessage(
    overrides: Partial<ChatMessageDto> = {},
  ): ChatMessageDto {
    return {
      timestamp: '2026-08-19T10:00:00.000Z',
      senderUsername: 'alice',
      privateChatId: 10,
      groupId: null,
      senderDeviceId: 'device-1',
      ...overrides,
    };
  }

  describe('id-based matching (clientMessageId present)', () => {
    it('treats two messages with the same clientMessageId as duplicates', () => {
      (component as any).messages = [
        {
          senderUsername: 'alice',
          privateChatId: 10,
          groupId: undefined,
          timestamp: '2026-08-19T10:00:00.000Z',
          clientMessageId: 'abc-123',
          content: 'hi',
          kind: 'text',
        },
      ];

      const incoming = baseMessage({ clientMessageId: 'abc-123' } as any);
      expect((component as any).isDuplicateMessage(incoming)).toBe(true);
    });

    it('does not treat messages with different clientMessageIds as duplicates', () => {
      (component as any).messages = [
        {
          senderUsername: 'alice',
          privateChatId: 10,
          groupId: undefined,
          timestamp: '2026-08-19T10:00:00.000Z',
          clientMessageId: 'abc-123',
          content: 'hi',
          kind: 'text',
        },
      ];

      const incoming = baseMessage({ clientMessageId: 'xyz-999' } as any);
      expect((component as any).isDuplicateMessage(incoming)).toBe(false);
    });
  });

  describe('fallback matching (no clientMessageId — legacy/pre-migration messages)', () => {
    it('regression test for #298: treats group message echo as duplicate despite undefined vs null privateChatId', () => {
      (component as any).messages = [
        {
          senderUsername: 'alice',
          groupId: 42,
          privateChatId: undefined,
          timestamp: '2026-08-19T10:00:00.000Z',
          content: 'hi',
          kind: 'text',
        },
      ];

      const incoming = baseMessage({
        privateChatId: null as any,
        groupId: 42,
      });

      expect((component as any).isDuplicateMessage(incoming)).toBe(true);
    });

    it('regression test for #298: treats private chat echo as duplicate despite undefined vs null groupId', () => {
      (component as any).messages = [
        {
          senderUsername: 'bob',
          groupId: undefined,
          privateChatId: 7,
          timestamp: '2026-08-19T10:05:00.000Z',
          content: 'yo',
          kind: 'text',
        },
      ];

      const incoming = baseMessage({
        senderUsername: 'bob',
        groupId: null as any,
        privateChatId: 7,
        timestamp: '2026-08-19T10:05:00.000Z',
      });

      expect((component as any).isDuplicateMessage(incoming)).toBe(true);
    });

    it('does not flag genuinely different messages (different sender) as duplicates', () => {
      (component as any).messages = [
        {
          senderUsername: 'alice',
          privateChatId: 10,
          groupId: undefined,
          timestamp: '2026-08-19T10:00:00.000Z',
          content: 'hi',
          kind: 'text',
        },
      ];

      const incoming = baseMessage({ senderUsername: 'carol' });
      expect((component as any).isDuplicateMessage(incoming)).toBe(false);
    });
  });

  it('end-to-end: sending a message then receiving its own echo does not duplicate it in the view', fakeAsync(() => {
    component.newMessage = 'hello there';

    const e2eeService = TestBed.inject(
      E2eeService,
    ) as jasmine.SpyObj<E2eeService>;
    e2eeService.encryptForDevices.and.resolveTo({
      senderDeviceId: 'device-1',
    } as any);
    (component as any).devices = [{ deviceId: 'device-1' } as DeviceDto];

    component.sendMessage();
    tick();

    expect(component.messages.length).toBe(1);
    const sentMessage = wsService.sendPrivateMessage.calls.mostRecent()
      .args[0] as ChatMessageDto;

    // Simulate the server echoing the same message back over the WebSocket
    privateMessages.next(sentMessage);
    tick();

    expect(component.messages.length).toBe(1);
    discardPeriodicTasks();
  }));
});
