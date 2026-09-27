import { CommonModule } from '@angular/common';
import { Component } from '@angular/core';
import { resolveVaultwardenUrl } from '../../config/external-domains.config';

interface ClientLink {
  label: string;
  url: string;
}

@Component({
  selector: 'app-password-manager',
  standalone: true,
  imports: [CommonModule],
  templateUrl: './password-manager.component.html',
  styleUrl: './password-manager.component.scss',
})
export class PasswordManagerComponent {
  readonly vaultwardenUrl = resolveVaultwardenUrl() ?? '';
  readonly clientLinks: ClientLink[] = [
    {
      label: 'Chrome',
      url: 'https://chromewebstore.google.com/detail/bitwarden-free-password-m/nngceckbapebfimnlniiiahkandclblb',
    },
    {
      label: 'Firefox',
      url: 'https://addons.mozilla.org/firefox/addon/bitwarden-password-manager/',
    },
    {
      label: 'Edge',
      url: 'https://microsoftedge.microsoft.com/addons/detail/bitwarden-free-password-m/jbkfoedolllekgbhcbcoahefnbanhhlh',
    },
    {
      label: 'Safari',
      url: 'https://apps.apple.com/app/bitwarden-password-manager/id1352778147',
    },
    {
      label: 'Apps',
      url: 'https://bitwarden.com/download/',
    },
  ];

  copyState: 'idle' | 'copied' | 'failed' = 'idle';

  get hasVaultwardenUrl(): boolean {
    return this.vaultwardenUrl.length > 0;
  }

  async copyServerUrl(): Promise<void> {
    if (!this.hasVaultwardenUrl || !navigator.clipboard) {
      this.copyState = 'failed';
      return;
    }

    try {
      await navigator.clipboard.writeText(this.vaultwardenUrl);
      this.copyState = 'copied';
      window.setTimeout(() => {
        this.copyState = 'idle';
      }, 2200);
    } catch {
      this.copyState = 'failed';
    }
  }
}
