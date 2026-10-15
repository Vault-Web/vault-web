import { Injectable } from '@angular/core';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { ChatMessageDto } from '../models/dtos/ChatMessageDto';
import { TypingIndicatorDto } from '../models/dtos/TypingIndicatorDto';
import { environment } from '../../environments/environment';
import { AuthService } from './auth.service';
import { Observable, Observer } from 'rxjs';
import { ChatMessageDeletedDto } from '../models/dtos/ChatMessageDeletedDto';
import { ChatErrorDto } from '../models/dtos/ChatErrorDto';

@Injectable({
  providedIn: 'root',
})
export class WebSocketService {
  private client: Client | undefined;
  private connected = false;
  private reconnectPromise: Promise<void> | null = null;
  private connectCallbacks: (() => void)[] = [];
  private privateSubscriptions: (() => void)[] = [];
  private hostUrl = environment.mainHostAddress;

  constructor(private auth: AuthService) {
    this.initClient();
  }

  private initClient() {
    this.client = new Client({
      webSocketFactory: () => {
        const token = this.auth.getToken() ?? '';
        return new SockJS(`${this.hostUrl}/ws-chat?token=${token}`);
      },
      reconnectDelay: 5000,
      beforeConnect: () => {
        const token = this.auth.getToken() ?? '';
        this.client!.connectHeaders = {
          Authorization: `Bearer ${token}`,
        };
      },
      onConnect: () => {
        this.connected = true;
        this.connectCallbacks.forEach((cb) => cb());
        this.connectCallbacks = [];
        [...this.privateSubscriptions].forEach((resubscribe) => {
          try {
            resubscribe();
          } catch (error) {
            console.error('Failed to restore subscription after reconnect', error);
          }
        });
      },
      onDisconnect: () => {
        this.connected = false;
      },
      onWebSocketClose: () => {
        this.connected = false;
      },
      onWebSocketError: () => {
        this.connected = false;
      },
      onStompError: (frame) => {
        console.error('Broker error: ', frame.headers['message']);
        console.error('Details: ', frame.body);
      },
    });

    this.client.activate();
  }

  ensureConnected(timeoutMs = 6000): Promise<boolean> {
    return this.ensureConnectedInternal(timeoutMs);
  }

  private async ensureConnectedInternal(timeoutMs: number): Promise<boolean> {
    if (this.connected) {
      return true;
    }

    if (!this.client) {
      this.initClient();
    }
    await this.forceReconnect();

    return new Promise((resolve) => {
      let resolved = false;
      const onConnected = () => {
        if (resolved) {
          return;
        }
        resolved = true;
        resolve(true);
      };

      this.connectCallbacks.push(onConnected);

      setTimeout(() => {
        if (resolved) {
          return;
        }
        this.connectCallbacks = this.connectCallbacks.filter(
          (cb) => cb !== onConnected,
        );
        resolve(false);
      }, timeoutMs);
    });
  }

  private async forceReconnect(): Promise<void> {
    if (this.reconnectPromise) {
      return this.reconnectPromise;
    }

    this.reconnectPromise = (async () => {
      if (!this.client) {
        this.initClient();
        return;
      }

      this.connected = false;
      try {
        if (this.client.active) {
          await this.client.deactivate();
        }
      } catch {
        // If deactivate fails, continue with a fresh activate attempt.
      }
      this.client.activate();
    })();

    try {
      await this.reconnectPromise;
    } finally {
      this.reconnectPromise = null;
    }
  }

  sendPrivateMessage(message: ChatMessageDto): boolean {
    if (this.connected) {
      this.client?.publish({
        destination: '/app/chat.private.send',
        body: JSON.stringify(message),
      });
      return true;
    } else {
      console.warn('WebSocket not connected yet. Message not sent.');
      return false;
    }
  }

  sendGroupMessage(message: ChatMessageDto): boolean {
    if (this.connected) {
      this.client?.publish({
        destination: '/app/chat.send',
        body: JSON.stringify(message),
      });
      return true;
    } else {
      console.warn('WebSocket not connected yet. Message not sent.');
      return false;
    }
  }

  subscribeToChatErrors(): Observable<ChatErrorDto> {
    return this.createReconnectableSubscription<ChatErrorDto>((observer) =>
      this.subscribeToChatErrorsInternal(observer),
    );
  }

  private subscribeToChatErrorsInternal(observer: Observer<ChatErrorDto>) {
    const subscription = this.client?.subscribe(
      '/user/queue/errors',
      (message) => {
        const event = JSON.parse(message.body) as ChatErrorDto;
        observer.next(event);
      },
    );

    return () => subscription?.unsubscribe();
  }

  sendDeleteMessage(clientMessageId: string): boolean {
    if (this.connected) {
      this.client?.publish({
        destination: '/app/chat.delete',
        body: clientMessageId,
      });
      return true;
    } else {
      console.warn('WebSocket not connected yet. Message not deleted.');
      return false;
    }
  }

  sendTypingIndicator(event: TypingIndicatorDto): boolean {
    if (this.connected) {
      this.client?.publish({
        destination: '/app/chat.typing',
        body: JSON.stringify(event),
      });
      return true;
    } else {
      console.warn('WebSocket not connected yet. Typing indicator not sent.');
      return false;
    }
  }

  subscribeToDeletedPrivateMessages(): Observable<ChatMessageDeletedDto> {
    return this.createReconnectableSubscription<ChatMessageDeletedDto>(
      (observer) => this.subscribeToDeletedPrivateInternal(observer),
    );
  }

  private subscribeToDeletedPrivateInternal(
    observer: Observer<ChatMessageDeletedDto>,
  ) {
    const subscription = this.client?.subscribe(
      '/user/queue/private/deleted',
      (message) => {
        const event = JSON.parse(message.body) as ChatMessageDeletedDto;
        observer.next(event);
      },
    );

    return () => subscription?.unsubscribe();
  }

  subscribeToPrivateMessages(): Observable<ChatMessageDto> {
    return this.createReconnectableSubscription<ChatMessageDto>((observer) =>
      this.subscribeInternal(observer),
    );
  }

  private subscribeInternal(observer: Observer<ChatMessageDto>) {
    const subscription = this.client?.subscribe(
      '/user/queue/private',
      (message) => {
        const msg = JSON.parse(message.body) as ChatMessageDto;
        observer.next(msg);
      },
    );

    return () => subscription?.unsubscribe();
  }

  subscribeToTypingIndicators(): Observable<TypingIndicatorDto> {
    return this.createReconnectableSubscription<TypingIndicatorDto>((observer) =>
      this.subscribeToTypingInternal(observer),
    );
  }

  private subscribeToTypingInternal(observer: Observer<TypingIndicatorDto>) {
    const subscription = this.client?.subscribe(
      '/user/queue/typing',
      (message) => {
        const event = JSON.parse(message.body) as TypingIndicatorDto;
        observer.next(event);
      },
    );

    return () => subscription?.unsubscribe();
  }

  subscribeToGroupMessages(groupId: number): Observable<ChatMessageDto> {
    return this.createReconnectableSubscription<ChatMessageDto>((observer) =>
      this.subscribeToGroupInternal(groupId, observer),
    );
  }

  subscribeToDeletedGroupMessages(
    groupId: number,
  ): Observable<ChatMessageDeletedDto> {
    return this.createReconnectableSubscription<ChatMessageDeletedDto>(
    (observer) => this.subscribeToDeletedGroupInternal(groupId, observer),
  );
  }

  private subscribeToDeletedGroupInternal(
    groupId: number,
    observer: Observer<ChatMessageDeletedDto>,
  ) {
    const subscription = this.client?.subscribe(
      `/topic/group/${groupId}/deleted`,
      (message) => {
        const event = JSON.parse(message.body) as ChatMessageDeletedDto;
        observer.next(event);
      },
    );

    return () => subscription?.unsubscribe();
  }

  private subscribeToGroupInternal(
    groupId: number,
    observer: Observer<ChatMessageDto>,
  ) {
    const subscription = this.client?.subscribe(
      `/topic/group/${groupId}`,
      (message) => {
        const msg = JSON.parse(message.body) as ChatMessageDto;
        observer.next(msg);
      },
    );

    return () => subscription?.unsubscribe();
  }

  private createReconnectableSubscription<T>(
    subscribeAction: (observer: Observer<T>) => () => void,
  ): Observable<T> {
    return new Observable<T>((observer) => {
      let unsubscribeFn: (() => void) | undefined;
      let isUnsubscribed = false;

      const sub = () => {
        if (isUnsubscribed) {
          return;
        }

        unsubscribeFn = subscribeAction(observer);
      };

      this.privateSubscriptions.push(sub);

      if (this.connected) {
        sub();
      }

      return () => {
        isUnsubscribed = true;

        this.privateSubscriptions = this.privateSubscriptions.filter(
          (subscription) => subscription !== sub,
        );

        try {
          unsubscribeFn?.();
        } catch {
          // Expected when the connection is already down; nothing to clean up.
        }
      };
    });
  }
}
