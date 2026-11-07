// Seeds the fixture surfaces onto the host Playwright started, through a JVM BoardClient
// (`:bee-host:e2eSeed`, whose body lands in Task 4).
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export default function globalSetup() {
  const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
  const host = `http://127.0.0.1:${process.env.BEE_E2E_PORT}`;
  execFileSync(join(repo, 'gradlew'), [':bee-host:e2eSeed', `-Pbee.e2e.host=${host}`, '--console=plain'], {
    cwd: repo,
    stdio: 'inherit',
  });
}
