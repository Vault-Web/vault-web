import { spawnSync } from 'node:child_process';
import {
  existsSync,
  mkdirSync,
  mkdtempSync,
  readdirSync,
  readFileSync,
  rmSync,
} from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, relative, resolve, sep } from 'node:path';

const frontendRoot = resolve(import.meta.dirname, '..');
const specPath = resolve(
  frontendRoot,
  process.env.CLOUD_OPENAPI_SPEC ??
    'src/app/api/contracts/cloud/openapi.yaml',
);
const outputPath = resolve(frontendRoot, 'src/app/api/generated/cloud');
const configPath = resolve(frontendRoot, 'openapi/cloud-generator-config.yaml');
const generatorVersion = 'v7.26.0';
const checkOnly = process.argv.includes('--check');

if (!existsSync(specPath)) {
  console.error(
    [
      'Cloud OpenAPI contract is not available.',
      'Expected a revision-pinned provider export at:',
      relative(frontendRoot, specPath),
      'Track the provider export prerequisite in:',
      'https://github.com/Vault-Web/cloud-page/issues/192',
      'Do not substitute a live /v3/api-docs endpoint or a mutable main/latest URL.',
    ].join('\n'),
  );
  process.exit(1);
}

const temporaryRoot = checkOnly
  ? mkdtempSync(join(tmpdir(), 'vault-cloud-openapi-'))
  : null;
const targetPath = checkOnly
  ? join(temporaryRoot, 'generated')
  : outputPath;

mkdirSync(dirname(targetPath), { recursive: true });

const dockerArgs = [
  'run',
  '--rm',
  '-v',
  `${frontendRoot}:/local`,
  '-v',
  `${temporaryRoot ?? frontendRoot}:/output`,
  `openapitools/openapi-generator-cli:${generatorVersion}`,
  'generate',
  '-g',
  'typescript-angular',
  '-i',
  `/local/${relative(frontendRoot, specPath).split(sep).join('/')}`,
  '-c',
  '/local/openapi/cloud-generator-config.yaml',
  '-o',
  checkOnly ? '/output/generated' : '/local/src/app/api/generated/cloud',
  '--global-property',
  'apiTests=false,modelTests=false,apiDocs=false,modelDocs=false',
];

try {
  const result = spawnSync('docker', dockerArgs, {
    cwd: frontendRoot,
    stdio: 'inherit',
  });

  if (result.error) {
    throw result.error;
  }
  if (result.status !== 0) {
    process.exit(result.status ?? 1);
  }

  if (checkOnly) {
    if (!existsSync(outputPath)) {
      console.error('Generated Cloud client is missing. Run npm run api:generate.');
      process.exitCode = 1;
    } else {
      const collectFiles = (root) => {
        const files = [];
        const visit = (directory) => {
          for (const entry of readdirSync(directory, { withFileTypes: true })) {
            const absolute = join(directory, entry.name);
            if (entry.isDirectory()) {
              visit(absolute);
            } else {
              files.push(relative(root, absolute).split(sep).join('/'));
            }
          }
        };
        visit(root);
        return files.sort();
      };

      const expected = collectFiles(outputPath);
      const actual = collectFiles(targetPath);
      const changed = [...new Set([...expected, ...actual])].filter(
        (file) =>
          !existsSync(join(outputPath, file)) ||
          !existsSync(join(targetPath, file)) ||
          !readFileSync(join(outputPath, file)).equals(
            readFileSync(join(targetPath, file)),
          ),
      );

      if (changed.length > 0) {
        console.error(
          'Generated Cloud client is stale. Changed files:\n' +
            changed.map((file) => ` - ${file}`).join('\n'),
        );
        process.exitCode = 1;
      } else {
        console.log('Generated Cloud client is up to date.');
      }
    }
  }
} finally {
  if (temporaryRoot) {
    rmSync(temporaryRoot, { recursive: true, force: true });
  }
}
