import { Injectable } from '@angular/core';
import { environment } from '../../environments/environment';

@Injectable({
  providedIn: 'root',
})
export class ChatCryptoService {
  private readonly encoder = new TextEncoder();
  private readonly decoder = new TextDecoder();
  private readonly keyPromise = this.importKey();

  async encrypt(content: string): Promise<{ cipherText: string; iv: string }> {
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await this.keyPromise;
    const encrypted = await crypto.subtle.encrypt(
      { name: 'AES-GCM', iv },
      key,
      this.encoder.encode(content),
    );

    return {
      cipherText: this.arrayBufferToBase64(encrypted),
      iv: this.uint8ToBase64(iv),
    };
  }

  async decrypt(cipherText: string, iv: string): Promise<string> {
    const key = await this.keyPromise;
    const decrypted = await crypto.subtle.decrypt(
      { name: 'AES-GCM', iv: this.base64ToUint8(iv) },
      key,
      this.base64ToArrayBuffer(cipherText),
    );

    return this.decoder.decode(decrypted);
  }

  private async importKey(): Promise<CryptoKey> {
    return crypto.subtle.importKey(
      'raw',
      this.base64ToUint8(environment.chatEncryptionKeyBase64),
      { name: 'AES-GCM' },
      false,
      ['encrypt', 'decrypt'],
    );
  }

  private arrayBufferToBase64(buffer: ArrayBuffer): string {
    return this.uint8ToBase64(new Uint8Array(buffer));
  }

  private base64ToArrayBuffer(value: string): ArrayBuffer {
    return this.base64ToUint8(value).buffer;
  }

  private uint8ToBase64(bytes: Uint8Array): string {
    let binary = '';
    bytes.forEach((b) => (binary += String.fromCharCode(b)));
    return btoa(binary);
  }

  private base64ToUint8(base64: string): Uint8Array {
    const binary = atob(base64);
    return Uint8Array.from(binary, (char) => char.charCodeAt(0));
  }
}
