import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { firstValueFrom } from 'rxjs';
import { environment } from '../../environments/environment';
import { DeviceDto } from '../models/dtos/DeviceDto';
import { AuthService } from './auth.service';

export interface E2eeRecipientPayload {
  iv: string;
  salt: string;
  ciphertext: string;
}

export interface E2eePayload {
  v: number;
  senderDeviceId: string;
  senderPublicKey: JsonWebKey;
  recipients: Record<string, E2eeRecipientPayload>;
}

interface StoredIdentity {
  deviceId: string;
  publicKey?: JsonWebKey;
  privateKey?: JsonWebKey;
  createdAt?: string;
}

@Injectable({
  providedIn: 'root',
})
export class E2eeService {
  private readonly deviceIdKey = 'vault.web.deviceId';
  private readonly deviceKeyPairKey = 'vault.web.deviceKeyPair';
  private readonly identitiesKeyPrefix = 'vault.web.identities';
  private readonly activeIdentityKeyPrefix = 'vault.web.activeIdentity';
  private readonly apiUrl = environment.mainApiUrl;
  private readonly encoder = new TextEncoder();
  private readonly decoder = new TextDecoder();
  private readonly hkdfInfo = this.encoder.encode('vault-web-e2ee-v1');
  private registrationPromise: Promise<void> | null = null;

  constructor(
    private http: HttpClient,
    private auth: AuthService,
  ) {}

  getDeviceId(): string {
    const username = this.auth.getUsername();
    if (!username) {
      const existing = localStorage.getItem(this.deviceIdKey);
      if (existing) {
        return existing;
      }
      const created = this.generateUuid();
      localStorage.setItem(this.deviceIdKey, created);
      return created;
    }
    const identity = this.ensureActiveIdentity(username);
    return identity.deviceId;
  }

  async ensureDeviceRegistered(): Promise<void> {
    if (this.registrationPromise) {
      return this.registrationPromise;
    }
    this.registrationPromise = this.registerDeviceInternal()
      .catch((error) => {
        this.registrationPromise = null;
        throw error;
      })
      .then(() => {
        this.registrationPromise = null;
      });
    return this.registrationPromise;
  }

  private async registerDeviceInternal(): Promise<void> {
    const token = await this.getAuthToken();
    if (!token) {
      throw new Error('Missing auth token for device registration');
    }
    const username = this.auth.getUsername();
    if (!username) {
      throw new Error('Missing username for device registration');
    }
    this.migrateLegacyIdentity(username);
    let identities = this.loadIdentities(username);
    if (!identities.length) {
      identities = [this.createIdentity(username)];
    }
    const attemptResult = await this.tryRegisterIdentities(
      identities,
      token,
      username,
    );
    if (attemptResult.registered) {
      return;
    }
    const newIdentity = this.createIdentity(username);
    const newAttempt = await this.tryRegisterIdentities(
      [newIdentity],
      token,
      username,
    );
    if (newAttempt.registered) {
      return;
    }
    const refreshedToken = await this.refreshAuthToken();
    if (!refreshedToken) {
      throw newAttempt.lastError ?? attemptResult.lastError;
    }
    const refreshedIdentities = this.loadIdentities(username);
    const refreshedAttempt = await this.tryRegisterIdentities(
      refreshedIdentities,
      refreshedToken,
      username,
    );
    if (!refreshedAttempt.registered) {
      throw (
        refreshedAttempt.lastError ??
        newAttempt.lastError ??
        attemptResult.lastError
      );
    }
  }

  private async getAuthToken(): Promise<string | null> {
    const token = this.auth.getToken();
    if (token) {
      return token;
    }
    return this.refreshAuthToken();
  }

  private async refreshAuthToken(): Promise<string | null> {
    try {
      const res = await firstValueFrom(this.auth.refresh());
      this.auth.saveToken(res.token);
      return res.token;
    } catch {
      return null;
    }
  }

  async encryptForDevices(
    plaintext: string,
    devices: DeviceDto[],
  ): Promise<E2eePayload> {
    const deviceId = this.getDeviceId();
    const keyPair = await this.getOrCreateKeyPair();
    const publicKeyJwk = await crypto.subtle.exportKey(
      'jwk',
      keyPair.publicKey,
    );
    const payload: E2eePayload = {
      v: 1,
      senderDeviceId: deviceId,
      senderPublicKey: publicKeyJwk,
      recipients: {},
    };

    await Promise.all(
      devices.map(async (device) => {
        const recipientPublicKey = await this.importPublicKey(
          JSON.parse(device.publicKey) as JsonWebKey,
        );
        const encrypted = await this.encryptForRecipient(
          plaintext,
          keyPair.privateKey,
          recipientPublicKey,
          this.buildAad(device.deviceId, deviceId, publicKeyJwk),
        );
        payload.recipients[device.deviceId] = encrypted;
      }),
    );

    return payload;
  }

  async decryptPayload(payloadString: string): Promise<string | null> {
    if (!payloadString) {
      return null;
    }
    try {
      const payload = JSON.parse(payloadString) as E2eePayload;
      const username = this.auth.getUsername();
      if (!username) {
        const deviceId = this.getDeviceId();
        const entry = payload.recipients[deviceId];
        if (!entry) {
          return null;
        }
        const keyPair = await this.getOrCreateKeyPair();
        const senderPublicKey = await this.importPublicKey(
          payload.senderPublicKey,
        );
        return this.decryptForRecipient(
          entry,
          keyPair.privateKey,
          senderPublicKey,
          this.buildAad(
            deviceId,
            payload.senderDeviceId,
            payload.senderPublicKey,
          ),
        );
      }
      this.migrateLegacyIdentity(username);
      const identities = this.loadIdentities(username);
      const senderPublicKey = await this.importPublicKey(
        payload.senderPublicKey,
      );
      for (const identity of identities) {
        const entry = payload.recipients[identity.deviceId];
        if (!entry) {
          continue;
        }
        try {
          const keyPair = await this.getOrCreateKeyPairForIdentity(
            username,
            identity,
          );
          const plaintext = await this.decryptForRecipient(
            entry,
            keyPair.privateKey,
            senderPublicKey,
            this.buildAad(
              identity.deviceId,
              payload.senderDeviceId,
              payload.senderPublicKey,
            ),
          );
          this.setActiveIdentityDeviceId(username, identity.deviceId);
          return plaintext;
        } catch {
          continue;
        }
      }
      return null;
    } catch {
      return null;
    }
  }

  private async getOrCreateKeyPair(): Promise<CryptoKeyPair> {
    const username = this.auth.getUsername();
    if (!username) {
      const stored = localStorage.getItem(this.deviceKeyPairKey);
      if (stored) {
        const parsed = JSON.parse(stored) as {
          publicKey: JsonWebKey;
          privateKey: JsonWebKey;
        };
        const publicKey = await crypto.subtle.importKey(
          'jwk',
          parsed.publicKey,
          { name: 'ECDH', namedCurve: 'P-256' },
          true,
          [],
        );
        const privateKey = await crypto.subtle.importKey(
          'jwk',
          parsed.privateKey,
          { name: 'ECDH', namedCurve: 'P-256' },
          true,
          ['deriveBits', 'deriveKey'],
        );
        return { publicKey, privateKey };
      }

      const keyPair = await crypto.subtle.generateKey(
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        ['deriveBits', 'deriveKey'],
      );
      const publicKey = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
      const privateKey = await crypto.subtle.exportKey(
        'jwk',
        keyPair.privateKey,
      );
      // Security note (v1): private key material is persisted for cross-reload continuity.
      // This is a known limitation and should be migrated to non-extractable IndexedDB storage.
      localStorage.setItem(
        this.deviceKeyPairKey,
        JSON.stringify({ publicKey, privateKey }),
      );
      return keyPair;
    }
    this.migrateLegacyIdentity(username);
    const identity = this.ensureActiveIdentity(username);
    return this.getOrCreateKeyPairForIdentity(username, identity);
  }

  private async importPublicKey(jwk: JsonWebKey): Promise<CryptoKey> {
    return crypto.subtle.importKey(
      'jwk',
      jwk,
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      [],
    );
  }

  private async encryptForRecipient(
    plaintext: string,
    senderPrivateKey: CryptoKey,
    recipientPublicKey: CryptoKey,
    aad: Uint8Array<ArrayBuffer>,
  ): Promise<E2eeRecipientPayload> {
    const sharedSecret = await crypto.subtle.deriveBits(
      { name: 'ECDH', public: recipientPublicKey },
      senderPrivateKey,
      256,
    );
    const salt = crypto.getRandomValues(new Uint8Array(16));
    const aesKey = await this.deriveAesKey(sharedSecret, salt.buffer);
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const ciphertext = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv, additionalData: aad },
      aesKey,
      this.encoder.encode(plaintext),
    );

    return {
      iv: this.arrayBufferToBase64(iv.buffer),
      salt: this.arrayBufferToBase64(salt.buffer),
      ciphertext: this.arrayBufferToBase64(ciphertext),
    };
  }

  private async decryptForRecipient(
    entry: E2eeRecipientPayload,
    recipientPrivateKey: CryptoKey,
    senderPublicKey: CryptoKey,
    aad: Uint8Array<ArrayBuffer>,
  ): Promise<string> {
    const sharedSecret = await crypto.subtle.deriveBits(
      { name: 'ECDH', public: senderPublicKey },
      recipientPrivateKey,
      256,
    );
    const salt = this.base64ToArrayBuffer(entry.salt);
    const aesKey = await this.deriveAesKey(sharedSecret, salt);
    const iv = new Uint8Array(this.base64ToArrayBuffer(entry.iv));
    const ciphertext = this.base64ToArrayBuffer(entry.ciphertext);
    let plaintext: ArrayBuffer;
    try {
      plaintext = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv, additionalData: aad },
        aesKey,
        ciphertext,
      );
    } catch {
      // Backward compatibility for payloads created before AAD was introduced.
      plaintext = await crypto.subtle.decrypt(
        { name: 'AES-GCM', iv },
        aesKey,
        ciphertext,
      );
    }
    return this.decoder.decode(plaintext);
  }

  private buildAad(
    recipientDeviceId: string,
    senderDeviceId: string,
    senderPublicKey: JsonWebKey,
  ): Uint8Array<ArrayBuffer> {
    const canonical = JSON.stringify({
      v: 1,
      recipientDeviceId,
      senderDeviceId,
      senderCrv: senderPublicKey.crv ?? '',
      senderKty: senderPublicKey.kty ?? '',
      senderX: senderPublicKey.x ?? '',
      senderY: senderPublicKey.y ?? '',
    });
    return this.encoder.encode(canonical);
  }

  private async deriveAesKey(
    sharedSecret: ArrayBuffer,
    salt: ArrayBuffer,
  ): Promise<CryptoKey> {
    const hkdfKey = await crypto.subtle.importKey(
      'raw',
      sharedSecret,
      'HKDF',
      false,
      ['deriveKey'],
    );
    return crypto.subtle.deriveKey(
      {
        name: 'HKDF',
        hash: 'SHA-256',
        salt,
        info: this.hkdfInfo,
      },
      hkdfKey,
      { name: 'AES-GCM', length: 256 },
      false,
      ['encrypt', 'decrypt'],
    );
  }

  private arrayBufferToBase64(buffer: ArrayBuffer): string {
    const bytes = new Uint8Array(buffer);
    const chunkSize = 0x8000;
    const chunks: string[] = [];
    for (let i = 0; i < bytes.length; i += chunkSize) {
      const chunk = bytes.subarray(i, i + chunkSize);
      chunks.push(String.fromCharCode(...chunk));
    }
    return btoa(chunks.join(''));
  }

  private base64ToArrayBuffer(base64: string): ArrayBuffer {
    const binary = atob(base64);
    const bytes = new Uint8Array(binary.length);
    for (let i = 0; i < binary.length; i += 1) {
      bytes[i] = binary.charCodeAt(i);
    }
    return bytes.buffer;
  }

  private loadIdentities(username: string): StoredIdentity[] {
    const raw = localStorage.getItem(this.identitiesStorageKey(username));
    if (!raw) {
      return [];
    }
    try {
      const parsed = JSON.parse(raw) as StoredIdentity[];
      return Array.isArray(parsed) ? parsed : [];
    } catch {
      return [];
    }
  }

  private saveIdentities(username: string, identities: StoredIdentity[]): void {
    localStorage.setItem(
      this.identitiesStorageKey(username),
      JSON.stringify(identities),
    );
  }

  private updateIdentity(username: string, identity: StoredIdentity): void {
    const identities = this.loadIdentities(username);
    const index = identities.findIndex(
      (entry) => entry.deviceId === identity.deviceId,
    );
    if (index >= 0) {
      identities[index] = identity;
    } else {
      identities.push(identity);
    }
    this.saveIdentities(username, identities);
  }

  private identitiesStorageKey(username: string): string {
    return `${this.identitiesKeyPrefix}.${username}`;
  }

  private activeIdentityStorageKey(username: string): string {
    return `${this.activeIdentityKeyPrefix}.${username}`;
  }

  private setActiveIdentityDeviceId(username: string, deviceId: string): void {
    localStorage.setItem(this.activeIdentityStorageKey(username), deviceId);
  }

  private ensureActiveIdentity(username: string): StoredIdentity {
    const identity = this.getActiveIdentity(username);
    if (identity) {
      return identity;
    }
    return this.createIdentity(username);
  }

  private getActiveIdentity(username: string): StoredIdentity | null {
    const identities = this.loadIdentities(username);
    if (!identities.length) {
      return null;
    }
    const activeId = localStorage.getItem(
      this.activeIdentityStorageKey(username),
    );
    if (activeId) {
      const match = identities.find((entry) => entry.deviceId === activeId);
      if (match) {
        return match;
      }
    }
    return identities[0];
  }

  private createIdentity(username: string): StoredIdentity {
    const identity: StoredIdentity = {
      deviceId: this.generateUuid(),
      createdAt: new Date().toISOString(),
    };
    const identities = this.loadIdentities(username);
    identities.push(identity);
    this.saveIdentities(username, identities);
    this.setActiveIdentityDeviceId(username, identity.deviceId);
    return identity;
  }

  private generateUuid(): string {
    const webCrypto = globalThis.crypto;
    if (webCrypto?.randomUUID) {
      return webCrypto.randomUUID();
    }

    const bytes = new Uint8Array(16);
    if (webCrypto?.getRandomValues) {
      webCrypto.getRandomValues(bytes);
    } else {
      for (let i = 0; i < bytes.length; i++) {
        bytes[i] = Math.floor(Math.random() * 256);
      }
    }

    // RFC 4122 version 4 variant bits.
    bytes[6] = (bytes[6] & 0x0f) | 0x40;
    bytes[8] = (bytes[8] & 0x3f) | 0x80;

    const hex = Array.from(bytes, (b) => b.toString(16).padStart(2, '0')).join(
      '',
    );
    return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20, 32)}`;
  }

  private migrateLegacyIdentity(username: string): void {
    const identities = this.loadIdentities(username);
    if (identities.length) {
      return;
    }
    const deviceId = localStorage.getItem(this.deviceIdKey);
    const keyPair = localStorage.getItem(this.deviceKeyPairKey);
    if (!deviceId || !keyPair) {
      return;
    }
    try {
      const parsed = JSON.parse(keyPair) as {
        publicKey: JsonWebKey;
        privateKey: JsonWebKey;
      };
      const migrated: StoredIdentity = {
        deviceId,
        publicKey: parsed.publicKey,
        privateKey: parsed.privateKey,
        createdAt: new Date().toISOString(),
      };
      this.saveIdentities(username, [migrated]);
      this.setActiveIdentityDeviceId(username, deviceId);
    } catch {
      return;
    }
  }

  private async getOrCreateKeyPairForIdentity(
    username: string,
    identity: StoredIdentity,
  ): Promise<CryptoKeyPair> {
    if (identity.publicKey && identity.privateKey) {
      const publicKey = await crypto.subtle.importKey(
        'jwk',
        identity.publicKey,
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        [],
      );
      const privateKey = await crypto.subtle.importKey(
        'jwk',
        identity.privateKey,
        { name: 'ECDH', namedCurve: 'P-256' },
        true,
        ['deriveBits', 'deriveKey'],
      );
      return { publicKey, privateKey };
    }

    const keyPair = await crypto.subtle.generateKey(
      { name: 'ECDH', namedCurve: 'P-256' },
      true,
      ['deriveBits', 'deriveKey'],
    );
    const publicKey = await crypto.subtle.exportKey('jwk', keyPair.publicKey);
    const privateKey = await crypto.subtle.exportKey('jwk', keyPair.privateKey);
    // Security note (v1): private key material is persisted for cross-reload continuity.
    // This is a known limitation and should be migrated to non-extractable IndexedDB storage.
    const updatedIdentity = {
      ...identity,
      publicKey,
      privateKey,
    };
    this.updateIdentity(username, updatedIdentity);
    return keyPair;
  }

  private async tryRegisterIdentities(
    identities: StoredIdentity[],
    token: string,
    username: string,
  ): Promise<{ registered: boolean; lastError: unknown }> {
    let lastError: unknown = null;
    for (const identity of identities) {
      const keyPair = await this.getOrCreateKeyPairForIdentity(
        username,
        identity,
      );
      const publicKeyJwk = await crypto.subtle.exportKey(
        'jwk',
        keyPair.publicKey,
      );
      try {
        await firstValueFrom(
          this.http.post<DeviceDto>(
            `${this.apiUrl}/devices/register`,
            {
              deviceId: identity.deviceId,
              publicKey: JSON.stringify(publicKeyJwk),
            },
            {
              headers: new HttpHeaders({
                Authorization: `Bearer ${token}`,
              }),
            },
          ),
        );
        this.setActiveIdentityDeviceId(username, identity.deviceId);
        return { registered: true, lastError: null };
      } catch (error) {
        lastError = error;
        const status =
          typeof error === 'object' && error !== null && 'status' in error
            ? (error as { status?: number }).status
            : null;
        if (status !== 401) {
          break;
        }
      }
    }
    return { registered: false, lastError };
  }
}
