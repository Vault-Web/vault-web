import { CommonModule } from '@angular/common';
import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewChild,
  QueryList,
  ViewChildren,
  AfterViewChecked,
  OnDestroy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChatMessageDto } from '../../models/dtos/ChatMessageDto';
import { TypingIndicatorDto } from '../../models/dtos/TypingIndicatorDto';
import { WebSocketService } from '../../services/web-socket.service';
import { PrivateChatService } from '../../services/private-chat.service';
import { Subscription } from 'rxjs/internal/Subscription';
import { E2eeService } from '../../services/e2ee.service';
import { DeviceDto } from '../../models/dtos/DeviceDto';
import { UiToastService } from '../../core/services/ui-toast.service';
import { GroupChatService } from '../../services/group-chat.service';
import { GroupService } from '../../services/group.service';
import { UserService } from '../../services/user.service';
import { GroupDto } from '../../models/dtos/GroupDto';
import { UserDto } from '../../models/dtos/UserDto';
import {
  CHAT_EMOJIS,
  CHAT_STICKERS,
  ChatSticker,
  findChatSticker,
} from './chat-reactions';
import { DomSanitizer, SafeHtml } from '@angular/platform-browser';
import DOMPurify from 'dompurify';

interface ChatMessageView {
  kind: 'text' | 'sticker';
  content: string;
  stickerId?: string;
  stickerSrc?: string;
  stickerLabel?: string;
  senderUsername?: string;
  privateChatId?: number;
  groupId?: number | null;
  timestamp: string;
}

interface EncryptedMessageBodyV1 {
  v: 1;
  text: string;
  clientTimestamp?: string;
}

interface EncryptedTextMessageBodyV2 {
  v: 2;
  kind: 'text';
  text: string;
  clientTimestamp?: string;
}

interface EncryptedStickerMessageBodyV2 {
  v: 2;
  kind: 'sticker';
  stickerId: string;
  alt?: string;
  clientTimestamp?: string;
}

type ParsedDecryptedMessageBody = {
  kind: 'text' | 'sticker';
  text: string | null;
  stickerId: string | null;
  stickerLabel: string | null;
  clientTimestamp: string | null;
};

@Component({
  selector: 'app-private-chat-dialog',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './private-chat-dialog.component.html',
  styleUrls: ['./private-chat-dialog.component.scss'],
})
export class PrivateChatDialogComponent
  implements OnInit, OnDestroy, AfterViewChecked
{
  @Input() username!: string;
  @Input() currentUsername!: string | null;
  @Input() privateChatId: number | null = null;
  @Input() groupId: number | null = null;
  @Input() groupName: string | null = null;
  @Input() otherUserPicUrl: string | null = null;
  @Input() currentUserPicUrl: string | null = null;
  @Output() closeChat = new EventEmitter<void>();

  messages: ChatMessageView[] = [];
  /** Rendered messages, keyed by their text; see formatMessage. */
  private readonly formattedMessages = new Map<string, SafeHtml>();
  newMessage = '';
  private devices: DeviceDto[] = [];
  private lastDevicesRefreshAt = 0;
  private readonly devicesCacheTtlMs = 15000;

  @ViewChild('messageContainer') messageContainer!: ElementRef<HTMLDivElement>;
  @ViewChild('messageInput') messageInput?: ElementRef<HTMLInputElement>;
  @ViewChild('searchInput') searchInput?: ElementRef<HTMLInputElement>;
  @ViewChildren('messageBubble') messageBubbles!: QueryList<
    ElementRef<HTMLDivElement>
  >;
  private messageSub?: Subscription;
  private typingIndicatorSub?: Subscription;

  private shouldScroll = false;
  isSearchOpen = false;
  searchQuery = '';
  matchedMessageIndexes: number[] = [];
  private matchedMessageIndexSet = new Set<number>();
  activeMatchPosition = -1;
  readonly emojis = CHAT_EMOJIS;
  readonly stickers = CHAT_STICKERS;
  isEmojiPickerOpen = false;
  isStickerPickerOpen = false;
  private isTyping = false;
  private typingIdleTimer: ReturnType<typeof setTimeout> | null = null;
  private typingStaleSweepTimer: ReturnType<typeof setInterval> | null = null;
  private readonly typingIdleTimeoutMs = 5000;
  private readonly typingStaleTimeoutMs = 8000;
  private readonly typingUsers = new Map<string, number>();

  // Group Management properties
  showGroupDetails = false;
  groupDetails: GroupDto | null = null;
  allUsers: UserDto[] = [];
  usersToInvite: UserDto[] = [];
  currentUserRole: 'ADMIN' | 'USER' = 'USER';
  isGroupAdmin = false;

  constructor(
    private wsService: WebSocketService,
    private chatService: PrivateChatService,
    private groupChatService: GroupChatService,
    private e2eeService: E2eeService,
    private toast: UiToastService,
    private groupService: GroupService,
    private userService: UserService,
    private sanitizer: DomSanitizer,
  ) {}

  ngOnInit(): void {
    if (this.groupId) {
      this.loadGroupDetails();
    }
    this.loadMessages();

    this.messageSub = this.subscribeToActiveMessages();

    this.typingIndicatorSub = this.wsService
      .subscribeToTypingIndicators()
      .subscribe((event) => this.handleTypingIndicator(event));
    this.typingStaleSweepTimer = setInterval(
      () => this.removeStaleTypingUsers(),
      2000,
    );

    void this.initializeE2ee();
  }

  ngOnDestroy(): void {
    this.stopTyping();
    this.messageSub?.unsubscribe();
    this.typingIndicatorSub?.unsubscribe();
    this.clearTypingTimers();
  }

  get chatTitle(): string {
    return this.isGroupChat ? this.username : `Chat with ${this.username}`;
  }

  private get isGroupChat(): boolean {
    return !!this.groupId;
  }

  private get activeConversationId(): number | undefined {
    return (this.isGroupChat ? this.groupId : this.privateChatId) ?? undefined;
  }

  private loadMessages(): void {
    const conversationId = this.activeConversationId;
    if (!conversationId) {
      this.toast.error('Chat unavailable', 'Could not identify this chat.');
      return;
    }

    const messages$ = this.isGroupChat
      ? this.groupChatService.getMessages(conversationId)
      : this.chatService.getMessages(conversationId);

    messages$.subscribe({
      next: (msgs) => {
        this.decryptMessages(msgs);
        this.shouldScroll = true;
      },
      error: () => {
        console.error('Error loading messages for chat');
        this.toast.error('Chat load failed', 'Could not load messages.');
      },
    });
  }

  private subscribeToActiveMessages(): Subscription {
    if (this.isGroupChat && this.groupId) {
      return this.wsService
        .subscribeToGroupMessages(this.groupId)
        .subscribe((msg) => {
          if (msg.groupId === this.groupId) {
            this.decryptAndAppendMessage(msg);
          }
        });
    }

    return this.wsService.subscribeToPrivateMessages().subscribe((msg) => {
      if (msg.privateChatId === this.privateChatId) {
        this.decryptAndAppendMessage(msg);
      }
    });
  }

  ngAfterViewChecked(): void {
    if (this.shouldScroll) {
      this.scrollToBottom();
      this.shouldScroll = false;
    }
  }

  scrollToBottom(): void {
    try {
      this.messageContainer.nativeElement.scrollTop =
        this.messageContainer.nativeElement.scrollHeight;
    } catch (err) {
      console.error('Scroll to bottom failed:', err);
    }
  }

  sendMessage(): void {
    if (!this.newMessage.trim()) return;

    this.stopTyping();
    void this.sendEncryptedTextMessage(this.newMessage);
  }

  onClose(): void {
    this.stopTyping();
    this.closeChat.emit();
  }

  onComposerInput(value: string): void {
    if (!value.trim()) {
      this.stopTyping();
      return;
    }

    if (!this.isTyping) {
      this.isTyping = true;
      this.sendTypingEvent('typing_start');
    }

    this.resetTypingIdleTimer();
  }

  get typingIndicatorLabel(): string {
    const names = Array.from(this.typingUsers.keys());

    if (names.length === 0) {
      return '';
    }

    if (names.length === 1) {
      return `${names[0]} is typing...`;
    }

    if (names.length === 2) {
      return `${names[0]} and ${names[1]} are typing...`;
    }

    return 'Several people are typing...';
  }

  private async initializeE2ee(): Promise<void> {
    try {
      await this.e2eeService.ensureDeviceRegistered();
      await this.refreshDevices();
    } catch {
      console.error('Failed to initialize end-to-end encryption');
      this.toast.warn(
        'Encryption setup issue',
        'Some messages may not be available until retry.',
      );
    }
  }

  private decryptMessages(messages: ChatMessageDto[]): void {
    Promise.all(
      messages.map(async (msg, index) => {
        try {
          return await this.toViewMessage(msg);
        } catch (err) {
          console.error(
            'Failed to decrypt message',
            {
              conversationId: this.activeConversationId,
              messageIndex: index,
            },
            err,
          );
          return null;
        }
      }),
    )
      .then((viewMessages) => {
        const successfulMessages = viewMessages.filter(
          (msg): msg is ChatMessageView => msg !== null,
        );
        if (successfulMessages.length !== viewMessages.length) {
          console.warn('Some messages failed to decrypt', {
            conversationId: this.activeConversationId,
            totalMessages: viewMessages.length,
            decryptedMessages: successfulMessages.length,
          });
        }
        this.formattedMessages.clear();
        this.messages = successfulMessages;
        this.applySearch();
        this.shouldScroll = true;
      })
      .catch((err) => {
        console.error(
          'Failed to decrypt one or more messages',
          this.activeConversationId,
          err,
        );
      });
  }

  private decryptAndAppendMessage(message: ChatMessageDto): void {
    if (this.isDuplicateMessage(message)) {
      return;
    }
    this.toViewMessage(message)
      .then((viewMessage) => {
        if (!viewMessage) {
          console.warn(
            'Failed to decrypt incoming message',
            this.activeConversationId,
          );
          return;
        }
        this.messages.push(viewMessage);
        if (viewMessage.senderUsername !== this.currentUsername) {
          this.typingUsers.delete(viewMessage.senderUsername ?? '');
        }
        this.applySearch();
        this.shouldScroll = !this.searchQuery.trim();
      })
      .catch((err) => {
        console.error(
          'Error decrypting incoming message',
          this.activeConversationId,
          err,
        );
      });
  }

  private async toViewMessage(
    message: ChatMessageDto,
  ): Promise<ChatMessageView | null> {
    let content = message.content ?? null;
    let timestamp = message.timestamp;
    let kind: ChatMessageView['kind'] = 'text';
    let stickerId: string | undefined;
    let stickerSrc: string | undefined;
    let stickerLabel: string | undefined;

    if (message.e2eePayload) {
      const decrypted = await this.e2eeService.decryptPayload(
        message.e2eePayload,
      );
      const parsed = this.parseDecryptedMessageBody(decrypted);
      content = parsed.text;
      kind = parsed.kind;
      if (parsed.clientTimestamp) {
        timestamp = parsed.clientTimestamp;
      }
      if (parsed.kind === 'sticker' && parsed.stickerId) {
        const sticker = findChatSticker(parsed.stickerId);
        stickerId = parsed.stickerId;
        stickerSrc = sticker?.src;
        stickerLabel = parsed.stickerLabel ?? sticker?.label ?? 'Sticker';
      }
    }
    if (!content) {
      content = message.e2eePayload
        ? 'Unable to decrypt message'
        : 'Encrypted message';
    }

    return {
      kind: stickerSrc ? kind : 'text',
      content,
      stickerId,
      stickerSrc,
      stickerLabel,
      senderUsername: message.senderUsername,
      privateChatId: message.privateChatId || undefined,
      groupId: message.groupId || undefined,
      timestamp,
    };
  }

  insertEmoji(emoji: string): void {
    const input = this.messageInput?.nativeElement;
    if (!input) {
      this.newMessage += emoji;
      return;
    }

    const start = input.selectionStart ?? this.newMessage.length;
    const end = input.selectionEnd ?? start;
    this.newMessage =
      this.newMessage.slice(0, start) + emoji + this.newMessage.slice(end);
    this.closeReactionPickers();

    setTimeout(() => {
      input.focus();
      const cursorPosition = start + emoji.length;
      input.setSelectionRange(cursorPosition, cursorPosition);
    }, 0);
  }

  sendSticker(sticker: ChatSticker): void {
    this.closeReactionPickers();
    void this.sendEncryptedStickerMessage(sticker);
  }

  toggleEmojiPicker(): void {
    this.isEmojiPickerOpen = !this.isEmojiPickerOpen;
    this.isStickerPickerOpen = false;
  }

  toggleStickerPicker(): void {
    this.isStickerPickerOpen = !this.isStickerPickerOpen;
    this.isEmojiPickerOpen = false;
  }

  closeReactionPickers(): void {
    this.isEmojiPickerOpen = false;
    this.isStickerPickerOpen = false;
  }

  private async sendEncryptedTextMessage(plaintext: string): Promise<void> {
    await this.sendEncryptedChatBody({
      kind: 'text',
      text: plaintext,
    });
  }

  private async sendEncryptedStickerMessage(
    sticker: ChatSticker,
  ): Promise<void> {
    await this.sendEncryptedChatBody({
      kind: 'sticker',
      stickerId: sticker.id,
      alt: sticker.label,
    });
  }

  private async sendEncryptedChatBody(
    body:
      | Omit<EncryptedTextMessageBodyV2, 'v' | 'clientTimestamp'>
      | Omit<EncryptedStickerMessageBodyV2, 'v' | 'clientTimestamp'>,
  ): Promise<void> {
    try {
      await this.e2eeService.ensureDeviceRegistered();
      this.devices = await this.fetchDevices();

      if (!this.devices.length) {
        this.devices = await this.fetchDevices(true);
        if (!this.devices.length) {
          console.error('No devices available for encryption');
          this.toast.error(
            'Message not sent',
            'No recipient devices available for encryption.',
          );
          return;
        }
      }

      const clientTimestamp = new Date().toISOString();
      const encryptedBody:
        | EncryptedTextMessageBodyV2
        | EncryptedStickerMessageBodyV2 = {
        v: 2,
        ...body,
        clientTimestamp,
      };
      const payload = await this.e2eeService.encryptForDevices(
        JSON.stringify(encryptedBody),
        this.devices,
      );

      const message: ChatMessageDto = {
        timestamp: clientTimestamp,
        senderUsername: this.currentUsername ? this.currentUsername : 'Unknown',
        privateChatId:
          (this.isGroupChat ? undefined : this.privateChatId) ?? undefined,
        groupId: this.isGroupChat ? this.groupId : null,
        senderDeviceId: payload.senderDeviceId,
        e2eePayload: JSON.stringify(payload),
      };

      const isConnected = await this.wsService.ensureConnected();
      if (!isConnected) {
        console.error('WebSocket not connected. Message not sent.');
        this.toast.error(
          'Message not sent',
          'Connection unavailable. Please try again.',
        );
        return;
      }

      const sent = this.isGroupChat
        ? this.wsService.sendGroupMessage(message)
        : this.wsService.sendPrivateMessage(message);
      if (!sent) {
        console.error('WebSocket not connected. Message not sent.');
        this.toast.error(
          'Message not sent',
          'Connection unavailable. Please try again.',
        );
        return;
      }
      this.decryptAndAppendMessage(message);
      if (body.kind === 'text') {
        this.newMessage = '';
      }
    } catch (error) {
      console.error('Failed to send encrypted message', error);
      this.toast.error('Message failed', 'Could not send message.');
    }
  }

  private async refreshDevices(): Promise<void> {
    this.devices = await this.fetchDevices(true);
  }

  private fetchDevices(forceRefresh = false): Promise<DeviceDto[]> {
    const isCacheFresh =
      Date.now() - this.lastDevicesRefreshAt < this.devicesCacheTtlMs;
    if (!forceRefresh && this.devices.length && isCacheFresh) {
      return Promise.resolve(this.devices);
    }
    return new Promise<DeviceDto[]>((resolve) => {
      const conversationId = this.activeConversationId;
      if (!conversationId) {
        resolve([]);
        return;
      }

      const devices$ = this.isGroupChat
        ? this.groupChatService.getDevices(conversationId)
        : this.chatService.getDevices(conversationId);

      devices$.subscribe({
        next: (devices) => {
          this.lastDevicesRefreshAt = Date.now();
          resolve(devices);
        },
        error: (error) => {
          console.error('Error loading devices for chat', error);
          resolve([]);
        },
      });
    });
  }

  toggleSearch() {
    this.isSearchOpen = !this.isSearchOpen;
    if (this.isSearchOpen) {
      setTimeout(() => this.searchInput?.nativeElement.focus(), 0);
    } else {
      this.searchQuery = '';
      this.applySearch();
      this.shouldScroll = true;
    }
  }

  applySearch(): void {
    const query = this.searchQuery.trim().toLowerCase();

    if (!query) {
      this.matchedMessageIndexes = [];
      this.matchedMessageIndexSet.clear();
      this.activeMatchPosition = -1;
      return;
    }

    this.matchedMessageIndexes = this.messages
      .map((msg, index) => ({ index, content: msg.content.toLowerCase() }))
      .filter((entry) => entry.content.includes(query))
      .map((entry) => entry.index);
    this.matchedMessageIndexSet = new Set(this.matchedMessageIndexes);

    this.activeMatchPosition = this.matchedMessageIndexes.length ? 0 : -1;
    this.scrollToActiveSearchMatch();
  }

  goToNextMatch(): void {
    if (!this.matchedMessageIndexes.length) {
      return;
    }
    this.activeMatchPosition =
      (this.activeMatchPosition + 1) % this.matchedMessageIndexes.length;
    this.scrollToActiveSearchMatch();
  }

  goToPreviousMatch(): void {
    if (!this.matchedMessageIndexes.length) {
      return;
    }
    this.activeMatchPosition =
      (this.activeMatchPosition - 1 + this.matchedMessageIndexes.length) %
      this.matchedMessageIndexes.length;
    this.scrollToActiveSearchMatch();
  }

  get searchMatchLabel(): string {
    if (!this.searchQuery.trim() || !this.matchedMessageIndexes.length) {
      return `0 / ${this.matchedMessageIndexes.length}`;
    }
    return `${this.activeMatchPosition + 1} / ${this.matchedMessageIndexes.length}`;
  }

  isMessageMatch(index: number): boolean {
    return this.matchedMessageIndexSet.has(index);
  }

  isActiveSearchMatch(index: number): boolean {
    if (this.activeMatchPosition < 0) {
      return false;
    }
    return this.matchedMessageIndexes[this.activeMatchPosition] === index;
  }

  private scrollToActiveSearchMatch(): void {
    if (this.activeMatchPosition < 0 || !this.messageBubbles.length) {
      return;
    }

    const messageIndex = this.matchedMessageIndexes[this.activeMatchPosition];
    const bubble = this.messageBubbles.get(messageIndex)?.nativeElement;
    if (!bubble) {
      return;
    }

    bubble.scrollIntoView({ behavior: 'smooth', block: 'center' });
  }

  private parseDecryptedMessageBody(
    decrypted: string | null,
  ): ParsedDecryptedMessageBody {
    if (!decrypted) {
      return this.emptyParsedMessage();
    }

    try {
      const parsed = JSON.parse(decrypted) as Partial<
        | EncryptedMessageBodyV1
        | EncryptedTextMessageBodyV2
        | EncryptedStickerMessageBodyV2
      >;
      if (parsed.v === 1 && typeof parsed.text === 'string') {
        const timestamp =
          typeof parsed.clientTimestamp === 'string' &&
          !Number.isNaN(Date.parse(parsed.clientTimestamp))
            ? parsed.clientTimestamp
            : null;
        return {
          kind: 'text',
          text: parsed.text,
          stickerId: null,
          stickerLabel: null,
          clientTimestamp: timestamp,
        };
      }

      if (
        parsed.v === 2 &&
        parsed.kind === 'text' &&
        typeof parsed.text === 'string'
      ) {
        return {
          kind: 'text',
          text: parsed.text,
          stickerId: null,
          stickerLabel: null,
          clientTimestamp: this.parseClientTimestamp(parsed.clientTimestamp),
        };
      }

      if (
        parsed.v === 2 &&
        parsed.kind === 'sticker' &&
        typeof parsed.stickerId === 'string'
      ) {
        const sticker = findChatSticker(parsed.stickerId);
        const label =
          typeof parsed.alt === 'string' && parsed.alt.trim()
            ? parsed.alt
            : (sticker?.label ?? 'Sticker');
        return {
          kind: 'sticker',
          text: label,
          stickerId: parsed.stickerId,
          stickerLabel: label,
          clientTimestamp: this.parseClientTimestamp(parsed.clientTimestamp),
        };
      }
    } catch {
      // Backward compatibility for older messages that encrypted raw plaintext only.
    }

    return {
      kind: 'text',
      text: decrypted,
      stickerId: null,
      stickerLabel: null,
      clientTimestamp: null,
    };
  }

  private emptyParsedMessage(): ParsedDecryptedMessageBody {
    return {
      kind: 'text',
      text: null,
      stickerId: null,
      stickerLabel: null,
      clientTimestamp: null,
    };
  }

  private escapeHtml(text: string): string {
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/"/g, '&quot;')
      .replace(/'/g, '&#39;');
  }

  /**
   * Rendered form of a message, with http/https URLs turned into links. The
   * template binds to this, so the result is cached per message text: otherwise
   * every change detection cycle re-escapes and re-sanitises every message on
   * screen and hands the binding a fresh object, repainting the whole log.
   */
  formatMessage(content?: string): SafeHtml {
    if (!content) {
      return '';
    }
    const cached = this.formattedMessages.get(content);
    if (cached !== undefined) {
      return cached;
    }
    const formatted = this.linkifyMessage(content);
    this.formattedMessages.set(content, formatted);
    return formatted;
  }

  private linkifyMessage(content: string): SafeHtml {
    // Match http/https URLs, then strip common trailing punctuation
    // that is usually not part of the URL (e.g. periods at end of sentences).
    const urlRegex = /(https?:\/\/[^\s]+)/g;

    let html = '';
    let lastIndex = 0;

    for (const match of content.matchAll(urlRegex)) {
      const originalUrl = match[0];
      const index = match.index ?? 0;

      // Strip trailing punctuation that is almost never part of a URL
      const url = originalUrl.replace(/[.,;:!?)]+$/, '');
      const trailing = originalUrl.slice(url.length);

      // Append escaped text before this URL
      html += this.escapeHtml(content.substring(lastIndex, index));

      // Build the anchor — escape the URL in both href and display text
      const safeUrl = this.escapeHtml(url);
      html += `<a href="${safeUrl}" target="_blank" rel="noopener noreferrer">${safeUrl}</a>${this.escapeHtml(trailing)}`;

      // Advance past the original match length
      lastIndex = index + originalUrl.length;
    }

    html += this.escapeHtml(content.substring(lastIndex));

    // DOMPurify strips target by default — allow it so links open in a new tab
    const cleanHtml = DOMPurify.sanitize(html, {
      ADD_ATTR: ['target'],
    });

    return this.sanitizer.bypassSecurityTrustHtml(cleanHtml);
  }

  private parseClientTimestamp(timestamp: string | undefined): string | null {
    return typeof timestamp === 'string' && !Number.isNaN(Date.parse(timestamp))
      ? timestamp
      : null;
  }

  private isDuplicateMessage(message: ChatMessageDto): boolean {
    return this.messages.some(
      (existing) =>
        existing.privateChatId === message.privateChatId &&
        existing.groupId === message.groupId &&
        existing.senderUsername === message.senderUsername &&
        existing.timestamp === message.timestamp,
    );
  }

  getAvatarPicUrl(senderUsername: string | undefined): string | null {
    if (!senderUsername) {
      return null;
    }
    return senderUsername === this.currentUsername
      ? this.currentUserPicUrl
      : this.otherUserPicUrl;
  }

  getAvatarFallback(senderUsername: string | undefined): string {
    if (senderUsername) {
      return senderUsername.charAt(0).toUpperCase();
    }
    return '?';
  }

  private handleTypingIndicator(event: TypingIndicatorDto): void {
    if (
      !this.isTypingEventForActiveChat(event) ||
      !event.username ||
      event.username === this.currentUsername
    ) {
      return;
    }

    if (event.type === 'typing_start') {
      this.typingUsers.set(
        event.username,
        Date.now() + this.typingStaleTimeoutMs,
      );
      return;
    }

    if (event.type === 'typing_stop') {
      this.typingUsers.delete(event.username);
    }
  }

  private resetTypingIdleTimer(): void {
    if (this.typingIdleTimer) {
      clearTimeout(this.typingIdleTimer);
    }
    this.typingIdleTimer = setTimeout(
      () => this.stopTyping(),
      this.typingIdleTimeoutMs,
    );
  }

  private stopTyping(): void {
    if (this.typingIdleTimer) {
      clearTimeout(this.typingIdleTimer);
      this.typingIdleTimer = null;
    }

    if (!this.isTyping) {
      return;
    }

    this.isTyping = false;
    this.sendTypingEvent('typing_stop');
  }

  private sendTypingEvent(type: TypingIndicatorDto['type']): void {
    this.wsService.sendTypingIndicator({
      type,
      privateChatId: this.isGroupChat ? null : this.privateChatId,
      groupId: this.isGroupChat ? this.groupId : null,
    });
  }

  private isTypingEventForActiveChat(event: TypingIndicatorDto): boolean {
    return this.isGroupChat
      ? event.groupId === this.groupId
      : event.privateChatId === this.privateChatId;
  }

  private removeStaleTypingUsers(): void {
    const now = Date.now();
    Array.from(this.typingUsers.entries()).forEach(([username, expiresAt]) => {
      if (expiresAt <= now) {
        this.typingUsers.delete(username);
      }
    });
  }

  private clearTypingTimers(): void {
    if (this.typingIdleTimer) {
      clearTimeout(this.typingIdleTimer);
      this.typingIdleTimer = null;
    }
    if (this.typingStaleSweepTimer) {
      clearInterval(this.typingStaleSweepTimer);
      this.typingStaleSweepTimer = null;
    }
  }

  // Group Management Methods
  toggleGroupDetails(): void {
    this.showGroupDetails = !this.showGroupDetails;
    if (this.showGroupDetails) {
      this.loadGroupDetails();
      this.loadAllUsers();
    }
  }

  loadGroupDetails(): void {
    if (!this.groupId) return;
    this.groupService.getGroupDetails(this.groupId).subscribe({
      next: (group) => {
        this.groupDetails = group;
        const currentMember = group.members?.find(
          (m) => m.user.username === this.currentUsername,
        );
        if (currentMember) {
          this.currentUserRole = currentMember.role;
          this.isGroupAdmin = currentMember.role === 'ADMIN';
        }
        this.filterInviteUsers();
      },
      error: (err) => {
        console.error('Error loading group details:', err);
      },
    });
  }

  loadAllUsers(): void {
    this.userService.getAllUsers().subscribe({
      next: (users) => {
        this.allUsers = users;
        this.filterInviteUsers();
      },
    });
  }

  filterInviteUsers(): void {
    if (!this.groupDetails || !this.allUsers.length) {
      this.usersToInvite = [];
      return;
    }
    const memberIds = new Set(
      this.groupDetails.members?.map((m) => m.user.id) || [],
    );
    this.usersToInvite = this.allUsers.filter((u) => !memberIds.has(u.id));
  }

  addMemberToGroup(userId: number): void {
    if (!this.groupId) return;
    this.groupService.addMember(this.groupId, userId).subscribe({
      next: () => {
        this.toast.success('Member added', 'User was added to the group.');
        this.loadGroupDetails();
        void this.refreshDevices();
      },
      error: (err) => {
        console.error('Error adding member to group:', err);
        this.toast.error('Failed to add member', 'Make sure you are an admin.');
      },
    });
  }

  removeMemberFromGroup(userId: number): void {
    if (!this.groupId) return;
    this.groupService.removeMember(this.groupId, userId).subscribe({
      next: () => {
        this.toast.success(
          'Member removed',
          'User was removed from the group.',
        );
        this.loadGroupDetails();
        void this.refreshDevices();
      },
      error: (err) => {
        console.error('Error removing member from group:', err);
        this.toast.error(
          'Failed to remove member',
          'Make sure you are an admin and at least one admin remains.',
        );
      },
    });
  }

  leaveGroup(): void {
    if (!this.groupId) return;
    this.groupService.leaveGroup(this.groupId).subscribe({
      next: () => {
        this.toast.success('Group left', 'You left the group.');
        this.onClose();
      },
      error: (err) => {
        console.error('Error leaving group:', err);
        this.toast.error(
          'Failed to leave group',
          'Make sure another admin exists if you are the last admin.',
        );
      },
    });
  }
}
