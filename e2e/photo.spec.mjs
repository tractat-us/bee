// The Photo Notion: an agent places one, a person's phone hands the page a shot through
// `<input type="file" capture="environment">`, and the photo reaches every page and the agent.
// Playwright's setInputFiles stands in for the camera app: the input sees a file either way.
// The agent is a real bee-mcp bridge (`:bee-mcp:installDist`), driven over stdio (agent.mjs).
import { test, expect } from '@playwright/test';
import { startAgent } from './agent.mjs';

const SURFACE = 'photo-whiteboard';
const SEEN = { timeout: 15_000 };

/** A string a fixture hides in its EXIF segment, as a phone hides a location there. */
const SECRET = 'BEE-GPS-40.7128N-74.0060W';

/**
 * A `width` × `height` JPEG with an EXIF segment holding SECRET, made on `page`'s canvas: a
 * gradient with a grid, so it compresses like a photo rather than like a flat fill.
 */
async function shotWithExif(page, width, height) {
  const base64 = await page.evaluate(([w, h]) => {
    const canvas = document.createElement('canvas');
    canvas.width = w;
    canvas.height = h;
    const pen = canvas.getContext('2d');
    const fill = pen.createLinearGradient(0, 0, w, h);
    fill.addColorStop(0, '#fdfcf7');
    fill.addColorStop(1, '#c9d6e8');
    pen.fillStyle = fill;
    pen.fillRect(0, 0, w, h);
    pen.strokeStyle = '#223';
    for (let x = 0; x < w; x += 40) pen.strokeRect(x, (x * 7) % h, 30, 30);
    return canvas.toDataURL('image/jpeg', 0.95).split(',')[1];
  }, [width, height]);
  const jpeg = Buffer.from(base64, 'base64');
  const payload = Buffer.concat([Buffer.from('Exif\0\0', 'latin1'), Buffer.from(SECRET, 'latin1')]);
  const length = Buffer.alloc(2);
  length.writeUInt16BE(payload.length + 2);
  // SOI, then an APP1 (EXIF) segment, then the rest of the JPEG.
  return Buffer.concat([jpeg.subarray(0, 2), Buffer.from([0xff, 0xe1]), length, payload, jpeg.subarray(2)]);
}

async function openPage(browser, name) {
  const context = await browser.newContext();
  const page = await context.newPage();
  await page.goto(`/?name=${name}`);
  await expect(page.locator('#status')).toHaveText(/^online/, SEEN);
  return { page, context };
}

test.afterAll(async () => {
  const janitor = await startAgent('photo-janitor');
  try {
    await janitor.call('bee_surface_close', { surfaceId: SURFACE }).catch(() => {});
  } finally {
    await janitor.end();
  }
});

test('a photo taken on one page shows on another, without its EXIF, and reaches the agent as an image', async ({ browser }) => {
  test.setTimeout(120_000);
  const agent = await startAgent('photo-agent');
  const phone = await openPage(browser, 'sam');
  const wall = await openPage(browser, 'kim');
  try {
    await agent.call('bee_surface_open', {
      surfaceId: SURFACE,
      title: 'The whiteboard',
      components: [{ id: 'root', component: 'Photo', prompt: 'A photo of the whiteboard', photo: { path: '/photo' } }],
    });
    await expect.poll(() => agent.call('bee_board', {}).then((b) => b.claims), { timeout: 30_000 }).toBe('online');

    const onPhone = phone.page.locator(`a2ui-surface[data-surface-id="${SURFACE}"] bee-photo`);
    const onWall = wall.page.locator(`a2ui-surface[data-surface-id="${SURFACE}"] bee-photo`);
    await expect(onPhone.locator('.prompt')).toHaveText('A photo of the whiteboard', SEEN);
    await expect(onPhone.locator('label.take')).toHaveText('Take a photo');
    const input = onPhone.locator('label.take input[type=file]');
    await expect(input).toHaveAttribute('capture', 'environment');
    await expect(input).toHaveAttribute('accept', 'image/*');

    const waited = agent.result('bee_wait_action', { surfaceId: SURFACE, timeoutSeconds: 45 });
    const shot = await shotWithExif(phone.page, 2400, 1800);
    expect(shot.includes(SECRET), 'the fixture carries its secret').toBe(true);
    await input.setInputFiles({ name: 'whiteboard.jpg', mimeType: 'image/jpeg', buffer: shot });

    // The other page shows it, downscaled to the long edge, with who took it.
    const image = onWall.locator('img');
    await expect(image).toBeVisible(SEEN);
    await expect(onWall.locator('.by')).toHaveText('sam');
    await expect(onWall.locator('label.retake')).toHaveText('Retake');
    await expect.poll(() => image.evaluate((img) => img.naturalWidth), SEEN).toBe(1600);
    const src = await image.getAttribute('src');
    expect(src.startsWith('data:image/jpeg;base64,'), 'a JPEG data URL').toBe(true);
    expect(src.length).toBeLessThanOrEqual(384 * 1024);
    const shown = Buffer.from(src.split(',')[1], 'base64');
    expect(shown.includes(SECRET), "the shown photo keeps none of the shot's EXIF").toBe(false);
    expect(shown.includes(Buffer.from('Exif\0', 'latin1')), 'the shown photo has no EXIF segment').toBe(false);

    // The agent's wait returns the record, light, and the photo itself as an image block.
    const result = await waited;
    const { record } = JSON.parse(result.content[0].text);
    expect(record?.name).toBe('photo');
    expect(record.context.type).toBe('image/jpeg');
    expect(record.context.image, "the record doesn't carry the bytes").toBeUndefined();
    const block = result.content.find((c) => c.type === 'image');
    expect(block?.mimeType).toBe('image/jpeg');
    expect(block.data).toBe(src.split(',')[1]);
    expect(record.context.bytes).toBe(shown.length);
    await agent.call('bee_action_done', { recordId: record.recordId, token: record.token });

    // bee_board names the photo without pouring its base64 into the agent's context.
    const board = await agent.call('bee_board', {});
    const surface = board.open.find((s) => s.surfaceId === SURFACE);
    expect(surface.data.photo.by).toBe('human:sam');
    expect(surface.data.photo.image).toMatch(/^\[photo: image\/jpeg, \d+ bytes; bee_wait_action returns it as an image\]$/);
  } finally {
    await phone.context.close();
    await wall.context.close();
    await agent.kill();
  }
});
