import { CommonModule } from '@angular/common';
import {
  Component,
  ElementRef,
  EventEmitter,
  Input,
  OnInit,
  Output,
  ViewChild,
  AfterViewChecked,
  OnDestroy,
} from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ChatMessageDto } from '../../models/dtos/ChatMessageDto';
import { WebSocketService } from '../../services/web-socket.service';
import { PrivateChatService } from '../../services/private-chat.service';
import { Subscription } from 'rxjs/internal/Subscription';
import { ChatCryptoService } from '../../services/chat-crypto.service';

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
  @Input() privateChatId!: number;
  @Output() closeChat = new EventEmitter<void>();

  messages: ChatMessageDto[] = [];
  newMessage = '';

  @ViewChild('messageContainer') messageContainer!: ElementRef<HTMLDivElement>;
  private privateMessageSub!: Subscription;

  private shouldScroll = false;

  constructor(
    private wsService: WebSocketService,
    private chatService: PrivateChatService,
    private chatCryptoService: ChatCryptoService,
  ) {}

  ngOnInit(): void {
    this.chatService.getMessages(this.privateChatId).subscribe({
      next: async (msgs) => {
        this.messages = await Promise.all(msgs.map((msg) => this.decryptMessage(msg)));
        this.shouldScroll = true;
      },
      error: () => {
        console.error('Error loading messages for private chat');
      },
    });

    this.privateMessageSub = this.wsService
      .subscribeToPrivateMessages()
      .subscribe(async (msg) => {
        if (msg.privateChatId === this.privateChatId) {
          this.messages.push(await this.decryptMessage(msg));
          this.shouldScroll = true;
        }
      });
  }

  ngOnDestroy(): void {
    this.privateMessageSub?.unsubscribe();
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

  async sendMessage(): Promise<void> {
    if (!this.newMessage.trim()) return;

    const encrypted = await this.chatCryptoService.encrypt(this.newMessage);
    const message: ChatMessageDto = {
      cipherText: encrypted.cipherText,
      iv: encrypted.iv,
      timestamp: new Date().toISOString(),
      senderUsername: this.currentUsername ? this.currentUsername : 'Unknown',
      privateChatId: this.privateChatId,
    };

    this.wsService.sendPrivateMessage(message);

    this.newMessage = '';
  }

  private async decryptMessage(message: ChatMessageDto): Promise<ChatMessageDto> {
    if (!message.cipherText || !message.iv) {
      return message;
    }

    return {
      ...message,
      content: await this.chatCryptoService.decrypt(message.cipherText, message.iv),
    };
  }

  onClose(): void {
    this.closeChat.emit();
  }
}
