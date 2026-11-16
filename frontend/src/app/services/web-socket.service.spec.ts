import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { AuthService } from './auth.service';
import { WebSocketService } from './web-socket.service';

describe('WebSocketService', () => {
  let service: WebSocketService;
  let authMock: jasmine.SpyObj<AuthService>;
  let subscribeSpy: jasmine.Spy;
  let client: Client;

  const fireConnect = () => {
    client.onConnect?.({} as never);
  };

  beforeEach(() => {
    authMock = jasmine.createSpyObj<AuthService>('AuthService', ['getToken']);
    authMock.getToken.and.returnValue('test-token');

    spyOn(Client.prototype, 'activate').and.stub();

    subscribeSpy = spyOn(Client.prototype, 'subscribe').and.callFake(
      () =>
        ({
          id: `subscription-${subscribeSpy.calls.count()}`,
          unsubscribe: jasmine.createSpy('unsubscribe'),
        }) as never,
    );

    service = new WebSocketService(authMock);
    client = service['client']!;
  });

  it('restores every active subscription after reconnecting', () => {
    const subscriptions = [
      service.subscribeToChatErrors().subscribe(),
      service.subscribeToDeletedPrivateMessages().subscribe(),
      service.subscribeToPrivateMessages().subscribe(),
      service.subscribeToTypingIndicators().subscribe(),
      service.subscribeToGroupMessages(10).subscribe(),
      service.subscribeToDeletedGroupMessages(10).subscribe(),
    ];

    fireConnect();

    expect(subscribeSpy.calls.count()).toBe(6);

    fireConnect();

    expect(subscribeSpy.calls.count()).toBe(12);

    const destinations = subscribeSpy.calls
      .allArgs()
      .map(([destination]) => destination);
    const expected = [
      '/user/queue/errors',
      '/user/queue/private/deleted',
      '/user/queue/private',
      '/user/queue/typing',
      '/topic/group/10',
      '/topic/group/10/deleted',
    ];
    expect(destinations).toEqual([...expected, ...expected]);

    subscriptions.forEach((subscription) => subscription.unsubscribe());
  });

  it('refreshes credentials only in the STOMP CONNECT headers', async () => {
    await client.beforeConnect(client);
    expect(client.connectHeaders).toEqual({
      Authorization: 'Bearer test-token',
    });
    authMock.getToken.and.returnValue('refreshed-token');
    await client.beforeConnect(client);
    expect(client.connectHeaders).toEqual({
      Authorization: 'Bearer refreshed-token',
    });
  });

  it('opens a token-free SockJS URL before and after token refresh', () => {
    spyOn(
      SockJS.prototype as unknown as { _receiveInfo: () => void },
      '_receiveInfo',
    ).and.stub();
    for (const token of ['test-token', 'refreshed-token']) {
      authMock.getToken.and.returnValue(token);
      const socket = client.webSocketFactory!() as unknown as {
        url: string;
        close: () => void;
      };
      expect(socket.url).toBe(`${service['hostUrl']}/ws-chat`);
      expect(socket.url).not.toContain(token);
      socket.close();
    }
  });

  it('does not restore a subscription after it is unsubscribed', () => {
    const subscription = service.subscribeToPrivateMessages().subscribe();

    fireConnect();

    expect(subscribeSpy.calls.count()).toBe(1);

    const fakeUnsubscribe =
      subscribeSpy.calls.mostRecent().returnValue.unsubscribe;

    subscription.unsubscribe();

    expect(fakeUnsubscribe).toHaveBeenCalledTimes(1);

    fireConnect();

    expect(subscribeSpy.calls.count()).toBe(1);
  });

  it('subscribes once when requested while disconnected', () => {
    const subscription = service.subscribeToPrivateMessages().subscribe();

    expect(subscribeSpy).not.toHaveBeenCalled();

    fireConnect();

    expect(subscribeSpy.calls.count()).toBe(1);

    expect(subscribeSpy).toHaveBeenCalledWith(
      '/user/queue/private',
      jasmine.any(Function),
    );

    subscription.unsubscribe();
  });

  it('releases the previous stale handle when reconnecting', () => {
    const subscription = service.subscribeToPrivateMessages().subscribe();

    fireConnect();
    expect(subscribeSpy.calls.count()).toBe(1);

    const firstFakeUnsubscribe =
      subscribeSpy.calls.mostRecent().returnValue.unsubscribe;

    fireConnect();
    expect(subscribeSpy.calls.count()).toBe(2);

    expect(firstFakeUnsubscribe).toHaveBeenCalledTimes(1);

    subscription.unsubscribe();
  });
});
