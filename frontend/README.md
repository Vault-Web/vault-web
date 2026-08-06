# Frontend

The frontend is an Angular-based application providing the web interface and dashboard for Vault Web.

## Requirements

To develop or run the frontend independently, you will need:

- Node.js (LTS recommended)
- npm (comes with Node.js)
- Angular CLI 19 (optional; the project includes a local CLI dependency through npm)

The project uses:

- Angular 19
- TypeScript 5.7
- Tailwind CSS 4
- PrimeNG 19

## Local Development

The frontend can run against either an HTTP or HTTPS backend.

### Default Local Mode (HTTP)

By default, `src/environments/environment.ts` is configured with:

- `useHttps = false`
- Backend base URL: `http://localhost:8080`

To start the development server:

```bash
npm install
npm start -- --ssl false
```

Open your browser at:

`http://localhost:4200`

Note: In this project, `npm start` defaults to running with SSL (`ng serve --ssl`). For HTTP development, explicitly pass `--ssl false`.

### HTTPS Dev Mode (Optional)

If your backend is running in HTTPS mode (`-Dspring-boot.run.profiles=dev`), update your environment file:

Set `useHttps = true` in `src/environments/environment.ts`.

Then start the frontend with SSL enabled:

```bash
npm start
```

Open your browser at:

`https://localhost:4200`

## Vault Web Runtime Configuration

### External Links (Navbar Dropdown / Mobile Menu)

External links are loaded dynamically at runtime from: public/runtime-config.local.js (gitignored).

Edit public/runtime-config.local.js to configure your custom external links.

Links can opt in to forwarding the current Vault Web access token in the URL
fragment. The target service must explicitly support this handoff:

```javascript
{
  name: "Habits",
  url: "http://localhost:9001/vault-web-login",
  forwardVaultWebToken: true,
}
```

## Running Tests

Execute the frontend unit test suite using the Karma test runner:

```bash
npm test
```

Or directly via the Angular CLI:

```bash
ng test
```

## Code Quality

Run ESLint checks using:

```bash
npm run lint
```

## Building

Create a production build with:

```bash
npm run build
```

The generated build artifacts are stored in the dist/ directory.

## Folder Structure

```text
frontend
├── public/                 # Public assets and runtime configuration files
│   └── stickers/           # Static sticker assets
│
└── src/
    ├── app/
    │   ├── config/         # Application configuration providers
    │   ├── core/           # Core HTTP interceptors, services, and utilities
    │   ├── models/         # TypeScript models and DTO interfaces
    │   ├── navbar/         # Navigation components and runtime link resolution
    │   ├── pages/          # Route views (dashboard, cloud, login, chat, etc.)
    │   └── services/       # Frontend services and API communication
    │
    ├── environments/       # Environment-specific configuration
    ├── main.ts             # Application entry point
    ├── polyfills.ts        # Browser compatibility setup
    └── styles.scss         # Global styling
```
