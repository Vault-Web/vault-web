import { HttpClient } from '@angular/common/http';
import { of } from 'rxjs';
import { DeviceDto } from '../models/dtos/DeviceDto';
import { AuthService } from './auth.service';
import { E2eeService } from './e2ee.service';

describe('E2eeService', () => {
  const DEVICE_ID_KEY = 'vault.web.deviceId';
  const DEVICE_KEYPAIR_KEY = 'vault.web.deviceKeyPair';

  let service: E2eeService;
  let authMock: jasmine.SpyObj<AuthService>;

  beforeEach(() => {
    localStorage.clear();

    authMock = jasmine.createSpyObj<AuthService>('AuthService', [
      'getUsername',
      'getToken',
      'refresh',
      'saveToken',
    ]);
    authMock.getUsername.and.returnValue(null);
    authMock.getToken.and.returnValue(null);
    authMock.refresh.and.returnValue(of({ token: 'token' }));

    service = new E2eeService({} as HttpClient, authMock);
  });

  afterEach(() => {
    localStorage.clear();
  });

  it('should encrypt and decrypt a real plaintext roundtrip for recipient device', async () => {
    const sender = await generateIdentity();
    const recipient = await generateIdentity();

    localStorage.setItem(DEVICE_ID_KEY, sender.deviceId);
    localStorage.setItem(
      DEVICE_KEYPAIR_KEY,
      JSON.stringify({
        publicKey: sender.publicKey,
        privateKey: sender.privateKey,
      }),
    );

    const devices: DeviceDto[] = [
      {
        deviceId: recipient.deviceId,
        publicKey: JSON.stringify(recipient.publicKey),
        userId: 2,
        username: 'recipient',
      },
    ];

    const plaintext = 'Hello E2EE Roundtrip äöü 123';
    const payload = await service.encryptForDevices(plaintext, devices);

    localStorage.setItem(DEVICE_ID_KEY, recipient.deviceId);
    localStorage.setItem(
      DEVICE_KEYPAIR_KEY,
      JSON.stringify({
        publicKey: recipient.publicKey,
        privateKey: recipient.privateKey,
      }),
    );

    const decrypted = await service.decryptPayload(JSON.stringify(payload));
    expect(decrypted).toBe(plaintext);
  });

  it('should return null when payload has no entry for current device', async () => {
    const sender = await generateIdentity();
    const recipient = await generateIdentity();
    const otherRecipient = await generateIdentity();

    localStorage.setItem(DEVICE_ID_KEY, sender.deviceId);
    localStorage.setItem(
      DEVICE_KEYPAIR_KEY,
      JSON.stringify({
        publicKey: sender.publicKey,
        privateKey: sender.privateKey,
      }),
    );

    const devices: DeviceDto[] = [
      {
        deviceId: otherRecipient.deviceId,
        publicKey: JSON.stringify(otherRecipient.publicKey),
        userId: 3,
        username: 'other',
      },
    ];

    const payload = await service.encryptForDevices('Secret', devices);

    localStorage.setItem(DEVICE_ID_KEY, recipient.deviceId);
    localStorage.setItem(
      DEVICE_KEYPAIR_KEY,
      JSON.stringify({
        publicKey: recipient.publicKey,
        privateKey: recipient.privateKey,
      }),
    );

    const decrypted = await service.decryptPayload(JSON.stringify(payload));
    expect(decrypted).toBeNull();
  });

  it('should decrypt older v1 payloads successfully (backward compatibility)', async () => {
    const sender = await generateIdentity();
    const recipient = await generateIdentity();

    const plaintext = 'Old v1 encrypted message';
    const encryptedV1 = await encryptForRecipientV1(
      plaintext,
      sender.privateKey,
      recipient.publicKey,
      recipient.deviceId,
      sender.deviceId,
      sender.publicKey,
    );

    const payloadV1 = {
      v: 1,
      senderDeviceId: sender.deviceId,
      senderPublicKey: sender.publicKey,
      recipients: {
        [recipient.deviceId]: encryptedV1,
      },
    };

    localStorage.setItem(DEVICE_ID_KEY, recipient.deviceId);
    localStorage.setItem(
      DEVICE_KEYPAIR_KEY,
      JSON.stringify({
        publicKey: recipient.publicKey,
        privateKey: recipient.privateKey,
      }),
    );

    const decrypted = await service.decryptPayload(JSON.stringify(payloadV1));
    expect(decrypted).toBe(plaintext);
  });

  it('should handle out-of-order messages and skipped keys', async () => {
    const sender = await generateIdentity();
    const recipient = await generateIdentity();

    localStorage.setItem(DEVICE_ID_KEY, sender.deviceId);
    localStorage.setItem(
      DEVICE_KEYPAIR_KEY,
      JSON.stringify({
        publicKey: sender.publicKey,
        privateKey: sender.privateKey,
      }),
    );

    const devices: DeviceDto[] = [
      {
        deviceId: recipient.deviceId,
        publicKey: JSON.stringify(recipient.publicKey),
        userId: 2,
        username: 'recipient',
      },
    ];

    // Encrypt three messages in sequence: 0, 1, 2
    const payload0 = await service.encryptForDevices('Message 0', devices);
    const payload1 = await service.encryptForDevices('Message 1', devices);
    const payload2 = await service.encryptForDevices('Message 2', devices);

    localStorage.setItem(DEVICE_ID_KEY, recipient.deviceId);
    localStorage.setItem(
      DEVICE_KEYPAIR_KEY,
      JSON.stringify({
        publicKey: recipient.publicKey,
        privateKey: recipient.privateKey,
      }),
    );

    // Decrypt Message 2 first (out of order). It should trigger ratcheting forward,
    // skipping keys for 0 and 1.
    const decrypted2 = await service.decryptPayload(JSON.stringify(payload2));
    expect(decrypted2).toBe('Message 2');

    // Decrypt Message 0 next (retrieved from skipped keys)
    const decrypted0 = await service.decryptPayload(JSON.stringify(payload0));
    expect(decrypted0).toBe('Message 0');

    // Decrypt Message 1 next (retrieved from skipped keys)
    const decrypted1 = await service.decryptPayload(JSON.stringify(payload1));
    expect(decrypted1).toBe('Message 1');

    // Trying to decrypt Message 0 again should return null (replay protection)
    const decrypted0Replay = await service.decryptPayload(
      JSON.stringify(payload0),
    );
    expect(decrypted0Replay).toBeNull();
  });
});

async function encryptForRecipientV1(
  plaintext: string,
  senderPrivateKeyJWK: JsonWebKey,
  recipientPublicKeyJWK: JsonWebKey,
  recipientDeviceId: string,
  senderDeviceId: string,
  senderPublicKeyJWK: JsonWebKey,
): Promise<{ iv: string; salt: string; ciphertext: string }> {
  const senderPrivateKey = await crypto.subtle.importKey(
    'jwk',
    senderPrivateKeyJWK,
    { name: 'ECDH', namedCurve: 'P-256' },
    true,
    ['deriveBits'],
  );
  const recipientPublicKey = await crypto.subtle.importKey(
    'jwk',
    recipientPublicKeyJWK,
    { name: 'ECDH', namedCurve: 'P-256' },
    true,
    [],
  );
  const sharedSecret = await crypto.subtle.deriveBits(
    { name: 'ECDH', public: recipientPublicKey },
    senderPrivateKey,
    256,
  );
  const salt = crypto.getRandomValues(new Uint8Array(16));

  // derive key using HKDF
  const hkdfKey = await crypto.subtle.importKey(
    'raw',
    sharedSecret,
    'HKDF',
    false,
    ['deriveKey'],
  );
  const info = new TextEncoder().encode('vault-web-e2ee-v1');
  const aesKey = await crypto.subtle.deriveKey(
    { name: 'HKDF', hash: 'SHA-256', salt: salt.buffer, info },
    hkdfKey,
    { name: 'AES-GCM', length: 256 },
    false,
    ['encrypt'],
  );

  const iv = crypto.getRandomValues(new Uint8Array(12));

  const canonical = JSON.stringify({
    v: 1,
    recipientDeviceId,
    senderDeviceId,
    senderCrv: senderPublicKeyJWK.crv ?? '',
    senderKty: senderPublicKeyJWK.kty ?? '',
    senderX: senderPublicKeyJWK.x ?? '',
    senderY: senderPublicKeyJWK.y ?? '',
  });
  const aad = new TextEncoder().encode(canonical);

  const ciphertext = await crypto.subtle.encrypt(
    { name: 'AES-GCM', iv, additionalData: aad },
    aesKey,
    new TextEncoder().encode(plaintext),
  );

  const arrayBufferToBase64 = (buffer: ArrayBuffer): string => {
    const bytes = new Uint8Array(buffer);
    const binary = Array.from(bytes, (b) => String.fromCharCode(b)).join('');
    return btoa(binary);
  };

  return {
    iv: arrayBufferToBase64(iv.buffer),
    salt: arrayBufferToBase64(salt.buffer),
    ciphertext: arrayBufferToBase64(ciphertext),
  };
}

async function generateIdentity(): Promise<{
  deviceId: string;
  publicKey: JsonWebKey;
  privateKey: JsonWebKey;
}> {
  const keyPair = await crypto.subtle.generateKey(
    { name: 'ECDH', namedCurve: 'P-256' },
    true,
    ['deriveBits', 'deriveKey'],
  );
  const publicKey = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
  const privateKey = await crypto.subtle.exportKey('jwk', keyPair.privateKey);
  return {
    deviceId: crypto.randomUUID(),
    publicKey,
    privateKey,
  };
}
