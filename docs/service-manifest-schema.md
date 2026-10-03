# Vault Web Service Manifest Schema

This document defines the standard JSON schema for external service dynamic integration manifests (`vault-service.json`).

Each service module in the Vault Web ecosystem (e.g. Cloud, Password Manager, Chats, Habits) must ship a conformant manifest file at its web root.

## Schema Definition

```json
{
  "$schema": "http://json-schema.org/draft-07/schema#",
  "title": "VaultServiceManifest",
  "type": "object",
  "properties": {
    "name": {
      "type": "string",
      "description": "Unique identifier of the service module (e.g. 'cloud')."
    },
    "displayName": {
      "type": "string",
      "description": "Human-readable name displayed in menus (e.g. 'Cloud')."
    },
    "icon": {
      "type": "string",
      "description": "PrimeIcons class name for navigation icons (e.g. 'pi-cloud')."
    },
    "route": {
      "type": "string",
      "description": "Local Angular route to activate the service view (e.g. '/cloud')."
    },
    "baseUrl": {
      "type": "string",
      "description": "Base URL where the service module is deployed (e.g. 'http://localhost:8090')."
    },
    "healthEndpoint": {
      "type": "string",
      "description": "Endpoint path to verify the module's operational state (e.g. 'http://localhost:8090/api/health')."
    },
    "requiredScopes": {
      "type": "array",
      "items": {
        "type": "string"
      },
      "description": "Required OAuth2/JWT scopes for interacting with this service."
    },
    "tokenForwarding": {
      "type": "object",
      "properties": {
        "enabled": {
          "type": "boolean",
          "description": "Whether the API gateway should forward the portal token to this service."
        },
        "type": {
          "type": "string",
          "enum": ["header", "cookie", "query"],
          "description": "Method of token transmission."
        },
        "name": {
          "type": "string",
          "description": "Header, cookie, or query parameter name for the token."
        }
      },
      "required": ["enabled"]
    }
  },
  "required": [
    "name",
    "displayName",
    "icon",
    "route",
    "baseUrl",
    "healthEndpoint"
  ]
}
```

## Example Manifest (`vault-service.json`)

```json
{
  "name": "cloud",
  "displayName": "Cloud",
  "icon": "pi-cloud",
  "route": "/cloud",
  "baseUrl": "http://localhost:8090",
  "healthEndpoint": "http://localhost:8090/api/health",
  "requiredScopes": ["cloud:read", "cloud:write"],
  "tokenForwarding": {
    "enabled": true,
    "type": "header",
    "name": "Authorization"
  }
}
```
