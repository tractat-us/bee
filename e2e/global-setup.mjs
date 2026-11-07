// Seeds the fixture surfaces onto the host Playwright started, through a JVM BoardClient
// (`:bee-host:e2eSeed`, E2eSeederMain). Playwright starts `webServer` before globalSetup, so
// the host is up; the seeder returns once a second client reads every fixture back from it.
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

export default function globalSetup() {
  const repo = join(dirname(fileURLToPath(import.meta.url)), '..');
  const host = `http://127.0.0.1:${process.env.BEE_E2E_PORT}`;
  execFileSync(join(repo, 'gradlew'), [':bee-host:e2eSeed', `-Pbee.e2e.host=${host}`, '--console=plain'], {
    // A seeder that can't reach the host fails the run instead of hanging it.
    timeout: 120_000,
    cwd: repo,
    stdio: 'inherit',
  });
}
