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
import { ChatMessageDeletedDto } from '../../models/dtos/ChatMessageDeletedDto';
import { TypingIndicatorDto } from '../../models/dtos/TypingIndicatorDto';
import { DeviceDto } from '../../models/dtos/DeviceDto';
import { E2eeService } from '../../services/e2ee.service';
import { GroupChatService } from '../../services/group-chat.service';
import { PrivateChatService } from '../../services/private-chat.service';
import { WebSocketService } from '../../services/web-socket.service';
import { PrivateChatDialogComponent } from './private-chat-dialog.component';
import { GroupService } from '../../services/group.service';
import { UserService } from '../../services/user.service';
import { By } from '@angular/platform-browser';

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
      'subscribeToDeletedPrivateMessages',
      'subscribeToDeletedGroupMessages',
      'sendDeleteMessage',
    ]);
    wsService.subscribeToPrivateMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToGroupMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToDeletedPrivateMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToDeletedGroupMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToTypingIndicators.and.returnValue(
      typingEvents.asObservable(),
    );
    wsService.sendTypingIndicator.and.returnValue(true);
    wsService.ensureConnected.and.resolveTo(true);
    wsService.sendPrivateMessage.and.returnValue(true);
    wsService.sendGroupMessage.and.returnValue(true);
    wsService.sendDeleteMessage.and.returnValue(true);

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
        'subscribeToDeletedPrivateMessages',
        'subscribeToDeletedGroupMessages',
        'sendDeleteMessage',
      ],
    );
    wsService.subscribeToPrivateMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToGroupMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToDeletedPrivateMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToDeletedGroupMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToTypingIndicators.and.returnValue(
      typingEvents.asObservable(),
    );
    wsService.sendTypingIndicator.and.returnValue(true);
    wsService.ensureConnected.and.resolveTo(true);
    wsService.sendPrivateMessage.and.returnValue(true);
    wsService.sendGroupMessage.and.returnValue(true);
    wsService.sendDeleteMessage.and.returnValue(true);

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
      'subscribeToDeletedPrivateMessages',
      'subscribeToDeletedGroupMessages',
      'sendDeleteMessage',
    ]);
    wsService.subscribeToPrivateMessages.and.returnValue(
      privateMessages.asObservable(),
    );
    wsService.subscribeToGroupMessages.and.returnValue(
      groupMessages.asObservable(),
    );
    wsService.subscribeToDeletedPrivateMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToDeletedGroupMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToTypingIndicators.and.returnValue(
      new Subject<TypingIndicatorDto>().asObservable(),
    );
    wsService.sendTypingIndicator.and.returnValue(true);
    wsService.ensureConnected.and.resolveTo(true);
    wsService.sendPrivateMessage.and.returnValue(true);
    wsService.sendGroupMessage.and.returnValue(true);
    wsService.sendDeleteMessage.and.returnValue(true);

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

describe('PrivateChatDialogComponent message deletion', () => {
  let fixture: ComponentFixture<PrivateChatDialogComponent>;
  let component: PrivateChatDialogComponent;
  let deletedPrivateMessages: Subject<ChatMessageDeletedDto>;
  let wsService: jasmine.SpyObj<WebSocketService>;
  let toast: jasmine.SpyObj<UiToastService>;

  beforeEach(async () => {
    deletedPrivateMessages = new Subject<ChatMessageDeletedDto>();
    wsService = jasmine.createSpyObj<WebSocketService>('WebSocketService', [
      'subscribeToPrivateMessages',
      'subscribeToTypingIndicators',
      'sendTypingIndicator',
      'ensureConnected',
      'sendPrivateMessage',
      'sendGroupMessage',
      'subscribeToGroupMessages',
      'subscribeToDeletedPrivateMessages',
      'subscribeToDeletedGroupMessages',
      'sendDeleteMessage',
    ]);
    wsService.subscribeToPrivateMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToGroupMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToDeletedPrivateMessages.and.returnValue(
      deletedPrivateMessages.asObservable(),
    );
    wsService.subscribeToDeletedGroupMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToTypingIndicators.and.returnValue(
      new Subject<TypingIndicatorDto>().asObservable(),
    );
    wsService.sendTypingIndicator.and.returnValue(true);
    wsService.ensureConnected.and.resolveTo(true);
    wsService.sendPrivateMessage.and.returnValue(true);
    wsService.sendGroupMessage.and.returnValue(true);
    wsService.sendDeleteMessage.and.returnValue(true);

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

    toast = jasmine.createSpyObj<UiToastService>('UiToastService', [
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

  function ownMessage(overrides: Partial<any> = {}) {
    return {
      senderUsername: 'alice',
      privateChatId: 10,
      groupId: undefined,
      timestamp: '2026-08-19T10:00:00.000Z',
      clientMessageId: 'own-msg-1',
      content: 'hi there',
      kind: 'text' as const,
      ...overrides,
    };
  }

  describe('deleteMessage guards', () => {
    it('does nothing if the message was not sent by the current user', () => {
      spyOn(window, 'confirm');
      component.deleteMessage(ownMessage({ senderUsername: 'bob' }));

      expect(window.confirm).not.toHaveBeenCalled();
      expect(wsService.sendDeleteMessage).not.toHaveBeenCalled();
    });

    it('does nothing if the message has no clientMessageId', () => {
      spyOn(window, 'confirm');
      component.deleteMessage(ownMessage({ clientMessageId: undefined }));

      expect(window.confirm).not.toHaveBeenCalled();
      expect(wsService.sendDeleteMessage).not.toHaveBeenCalled();
    });

    it('does nothing if the message is already marked deleted', () => {
      spyOn(window, 'confirm');
      component.deleteMessage(ownMessage({ deleted: true }));

      expect(window.confirm).not.toHaveBeenCalled();
      expect(wsService.sendDeleteMessage).not.toHaveBeenCalled();
    });

    it('does not send a delete request if the user cancels the confirmation', () => {
      spyOn(window, 'confirm').and.returnValue(false);
      component.deleteMessage(ownMessage());

      expect(window.confirm).toHaveBeenCalled();
      expect(wsService.sendDeleteMessage).not.toHaveBeenCalled();
    });

    it('sends the delete request with the correct clientMessageId when confirmed', () => {
      spyOn(window, 'confirm').and.returnValue(true);
      component.deleteMessage(ownMessage({ clientMessageId: 'own-msg-42' }));

      expect(wsService.sendDeleteMessage).toHaveBeenCalledWith('own-msg-42');
    });

    it('shows an error toast if the socket is not connected', () => {
      spyOn(window, 'confirm').and.returnValue(true);
      wsService.sendDeleteMessage.and.returnValue(false);

      component.deleteMessage(ownMessage());

      expect(toast.error).toHaveBeenCalled();
    });
  });

  describe('handling an incoming delete event', () => {
    it('marks the matching message as deleted with placeholder content, leaving others untouched', () => {
      (component as any).messages = [
        ownMessage({ clientMessageId: 'msg-1', content: 'first' }),
        ownMessage({ clientMessageId: 'msg-2', content: 'second' }),
      ];

      deletedPrivateMessages.next({ clientMessageId: 'msg-1' });

      const [first, second] = component.messages as any[];
      expect(first.deleted).toBe(true);
      expect(first.content).toBe('Message deleted');
      expect(second.deleted).toBeFalsy();
      expect(second.content).toBe('second');
    });

    it('clears sticker fields on a deleted sticker message so the placeholder renders instead', () => {
      (component as any).messages = [
        ownMessage({
          clientMessageId: 'sticker-1',
          kind: 'sticker',
          stickerId: 'cat',
          stickerSrc: '/stickers/cat.png',
          stickerLabel: 'Cat',
        }),
      ];

      deletedPrivateMessages.next({ clientMessageId: 'sticker-1' });

      const [message] = component.messages as any[];
      expect(message.deleted).toBe(true);
      expect(message.stickerSrc).toBeUndefined();
      expect(message.stickerId).toBeUndefined();
      expect(message.stickerLabel).toBeUndefined();
    });

    it('does nothing if no message matches the deleted clientMessageId', () => {
      (component as any).messages = [
        ownMessage({ clientMessageId: 'msg-1', content: 'first' }),
      ];

      deletedPrivateMessages.next({ clientMessageId: 'does-not-exist' });

      const [first] = component.messages as any[];
      expect(first.deleted).toBeFalsy();
      expect(first.content).toBe('first');
    });
  });
});

describe('PrivateChatDialogComponent emoji picker', () => {
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
        'subscribeToDeletedPrivateMessages',
        'subscribeToDeletedGroupMessages',
        'sendDeleteMessage',
      ],
    );
    wsService.subscribeToPrivateMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToGroupMessages.and.returnValue(of<ChatMessageDto>());
    wsService.subscribeToDeletedPrivateMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToDeletedGroupMessages.and.returnValue(
      of<ChatMessageDeletedDto>(),
    );
    wsService.subscribeToTypingIndicators.and.returnValue(
      typingEvents.asObservable(),
    );
    wsService.sendTypingIndicator.and.returnValue(true);
    wsService.ensureConnected.and.resolveTo(true);
    wsService.sendPrivateMessage.and.returnValue(true);
    wsService.sendGroupMessage.and.returnValue(true);
    wsService.sendDeleteMessage.and.returnValue(true);

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

  it('toggles the emoji picker open and closed', () => {
    expect(component.isEmojiPickerOpen).toBe(false);

    component.toggleEmojiPicker();
    expect(component.isEmojiPickerOpen).toBe(true);

    component.toggleEmojiPicker();
    expect(component.isEmojiPickerOpen).toBe(false);
  });

  it('renders app-emoji-picker only while the picker is open', () => {
    expect(fixture.debugElement.query(By.css('app-emoji-picker'))).toBeFalsy();

    component.toggleEmojiPicker();
    fixture.detectChanges();

    expect(fixture.debugElement.query(By.css('app-emoji-picker'))).toBeTruthy();
  });

  it('passes the current username as the storageKey to the emoji picker', () => {
    component.toggleEmojiPicker();
    fixture.detectChanges();

    const picker = fixture.debugElement.query(By.css('app-emoji-picker'));
    expect(picker.componentInstance.storageKey).toBe('alice');
  });

  describe('insertEmoji (caret-position insertion)', () => {
    function setCaret(el: HTMLTextAreaElement, position: number): void {
      el.setSelectionRange(position, position);
    }

    it('inserts the emoji at the caret position, splitting the surrounding text', fakeAsync(() => {
      component.newMessage = 'hello world';
      fixture.detectChanges();

      const input = (component as any).messageInput
        .nativeElement as HTMLTextAreaElement;
      input.value = component.newMessage;
      setCaret(input, 5); // right after "hello"

      component.insertEmoji('😀');
      tick();

      expect(component.newMessage).toBe('hello😀 world');
    }));

    it('inserts at the very start when the caret is at position 0', fakeAsync(() => {
      component.newMessage = 'world';
      fixture.detectChanges();

      const input = (component as any).messageInput
        .nativeElement as HTMLTextAreaElement;
      input.value = component.newMessage;
      setCaret(input, 0);

      component.insertEmoji('👍');
      tick();

      expect(component.newMessage).toBe('👍world');
    }));

    it('inserts at the end when the caret is at the end of the text', fakeAsync(() => {
      component.newMessage = 'hello';
      fixture.detectChanges();

      const input = (component as any).messageInput
        .nativeElement as HTMLTextAreaElement;
      input.value = component.newMessage;
      setCaret(input, input.value.length);

      component.insertEmoji('🔥');
      tick();

      expect(component.newMessage).toBe('hello🔥');
    }));

    it('replaces a selected range rather than inserting alongside it', fakeAsync(() => {
      component.newMessage = 'hello world';
      fixture.detectChanges();

      const input = (component as any).messageInput
        .nativeElement as HTMLTextAreaElement;
      input.value = component.newMessage;
      input.setSelectionRange(0, 5); // select "hello"

      component.insertEmoji('👋');
      tick();

      expect(component.newMessage).toBe('👋 world');
    }));

    it('refocuses the message input after inserting an emoji', fakeAsync(() => {
      component.newMessage = 'hi';
      fixture.detectChanges();

      const input = (component as any).messageInput
        .nativeElement as HTMLTextAreaElement;
      input.value = component.newMessage;
      setCaret(input, 2);

      component.insertEmoji('🎉');
      tick();

      expect(document.activeElement).toBe(input);
    }));

    it('moves the caret to just after the inserted emoji', fakeAsync(() => {
      component.newMessage = 'hi there';
      fixture.detectChanges();

      const input = (component as any).messageInput
        .nativeElement as HTMLTextAreaElement;
      input.value = component.newMessage;
      setCaret(input, 2); // right after "hi"

      component.insertEmoji('🎉');
      tick();

      const expectedCaret = 'hi🎉'.length;
      expect(input.selectionStart).toBe(expectedCaret);
      expect(input.selectionEnd).toBe(expectedCaret);
    }));
  });

  it('wires the emoji picker output to insertEmoji', fakeAsync(() => {
    component.newMessage = 'test';
    fixture.detectChanges();

    const input = (component as any).messageInput
      .nativeElement as HTMLTextAreaElement;
    input.value = component.newMessage;
    input.setSelectionRange(4, 4);

    const insertSpy = spyOn(component, 'insertEmoji').and.callThrough();

    component.toggleEmojiPicker();
    fixture.detectChanges();

    const picker = fixture.debugElement.query(By.css('app-emoji-picker'));
    picker.triggerEventHandler('emojiSelected', '😀');
    tick();

    expect(insertSpy).toHaveBeenCalledWith('😀');
  }));
});
