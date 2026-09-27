export interface ExternalDomainLink {
  name: string;
  url: string;
  forwardVaultWebToken: boolean;
}

declare global {
  interface Window {
    __VAULT_WEB_EXTERNAL_LINKS__?: unknown;
    __VAULT_WEB_VAULTWARDEN_URL__?: unknown;
  }
}

function isValidLink(
  value: unknown,
): value is { name: string; url: string; forwardVaultWebToken?: boolean } {
  if (typeof value !== 'object' || value === null) {
    return false;
  }

  const candidate = value as {
    name?: unknown;
    url?: unknown;
    forwardVaultWebToken?: unknown;
  };
  return (
    typeof candidate.name === 'string' &&
    candidate.name.trim().length > 0 &&
    typeof candidate.url === 'string' &&
    candidate.url.trim().length > 0 &&
    (candidate.forwardVaultWebToken === undefined ||
      typeof candidate.forwardVaultWebToken === 'boolean')
  );
}

function resolveExternalLinks(): ExternalDomainLink[] {
  const links = window.__VAULT_WEB_EXTERNAL_LINKS__;
  if (!Array.isArray(links)) {
    return [];
  }

  return links.filter(isValidLink).map((link) => ({
    name: link.name.trim(),
    url: link.url.trim(),
    forwardVaultWebToken: link.forwardVaultWebToken === true,
  }));
}

export const EXTERNAL_DOMAIN_LINKS: ExternalDomainLink[] =
  resolveExternalLinks();

export function resolveVaultwardenUrl(): string | null {
  if (
    typeof window.__VAULT_WEB_VAULTWARDEN_URL__ === 'string' &&
    window.__VAULT_WEB_VAULTWARDEN_URL__.trim().length > 0
  ) {
    return window.__VAULT_WEB_VAULTWARDEN_URL__.trim();
  }

  return (
    EXTERNAL_DOMAIN_LINKS.find((link) => {
      const name = link.name.toLowerCase();
      return name.includes('password') || name.includes('vaultwarden');
    })?.url ?? null
  );
}

export function resolveExternalLinkUrl(
  link: ExternalDomainLink,
  vaultWebToken: string | null,
): string {
  if (!link.forwardVaultWebToken || !vaultWebToken) {
    return link.url;
  }

  const separator = link.url.includes('#') ? '&' : '#';
  return `${link.url}${separator}vaultWebToken=${encodeURIComponent(vaultWebToken)}`;
}
