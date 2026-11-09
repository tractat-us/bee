// `Photo`: one shot from a person's phone camera, on the board for everyone (bee-a2ui's Photo.kt).
// An agent places it with an optional prompt ("a photo of the whiteboard"). Until someone takes
// one it shows the prompt and one quiet "Take a photo"; once filled, the photo, who took it, and
// a small "Retake".
//
// The camera opens through `<input type="file" accept="image/*" capture="environment">`, the HTML
// Media Capture attribute, which iOS Safari (10+) and Chrome for Android (25+) honour by opening
// the rear camera (MDN browser-compat-data, `html.elements.input.capture`). Nothing in that spec
// asks for a secure context, unlike `getUserMedia`, which is undefined on a plain-HTTP LAN page.
// A desktop browser shows its file picker instead.
//
// The shot is drawn onto a canvas and encoded as JPEG. That shrinks it to the board's budget, and
// it strips EXIF, location included, since a canvas holds only pixels. The data URL then goes out
// as the A2UI action `photo` with context `{image}`, and the page's driver writes it to the bound
// `photo` path as `{image, by}`.
import { A2uiLitElement } from '@a2ui/lit/v0_9';
import { DynamicValueSchema, TextApi } from '@a2ui/web_core/v0_9';
import { html, nothing } from 'lit';
import { BoundData } from './bound-data.js';
import { peerName } from './poll.js';

/** The A2UI action taking a photo becomes (Photo.TAKE). */
export const TAKE_ACTION = 'photo';

/** The longest data URL a Photo holds, in characters (Photo.MAX_IMAGE_CHARS): keep the two in step. */
export const MAX_IMAGE_CHARS = 384 * 1024;

/** The longest edge the page encodes first, in pixels: enough to read a whiteboard's handwriting. */
export const LONG_EDGE = 1600;

/** Below this long edge the page gives up rather than send an unreadable photo. */
const SMALLEST_EDGE = 400;

/** The JPEG qualities tried at each size, best first. */
const QUALITIES = [0.82, 0.7, 0.58];

/** A data URL a Photo will show: JPEG or WebP, base64, within the budget. Never SVG, which can carry script. */
export function showable(image) {
  return typeof image === 'string'
    && image.length <= MAX_IMAGE_CHARS
    && /^data:image\/(jpeg|webp);base64,[A-Za-z0-9+/]+={0,2}$/.test(image);
}

/** `width × height` scaled to fit `edge` on its longer side, never scaled up. */
export function fitWithin(width, height, edge) {
  const scale = Math.min(1, edge / Math.max(width, height));
  return { width: Math.max(1, Math.round(width * scale)), height: Math.max(1, Math.round(height * scale)) };
}

/**
 * Encodes `source` (anything `drawImage` takes, `width` × `height` pixels) as the largest JPEG
 * data URL within MAX_IMAGE_CHARS: LONG_EDGE at the best quality that fits, then smaller.
 * `encode(canvas, quality)` is the canvas's `toDataURL`, a seam for tests.
 *
 * @throws Error if even SMALLEST_EDGE at the lowest quality is over the budget
 */
export function encodeWithin(source, width, height, makeCanvas = () => document.createElement('canvas'),
  encode = (canvas, quality) => canvas.toDataURL('image/jpeg', quality)) {
  for (let edge = LONG_EDGE; edge >= SMALLEST_EDGE; edge = Math.round(edge * 0.75)) {
    const size = fitWithin(width, height, edge);
    const canvas = makeCanvas();
    canvas.width = size.width;
    canvas.height = size.height;
    const pen = canvas.getContext('2d');
    // JPEG has no transparency: a transparent pixel would turn black.
    pen.fillStyle = '#fff';
    pen.fillRect(0, 0, size.width, size.height);
    pen.drawImage(source, 0, 0, size.width, size.height);
    for (const quality of QUALITIES) {
      const image = encode(canvas, quality);
      if (image.length <= MAX_IMAGE_CHARS) return image;
    }
  }
  throw new Error("This photo can't be made small enough for the board.");
}

/**
 * The shot in `file` as a data URL within the budget. It decodes through an `<img>`, which every
 * current browser draws in its EXIF orientation, so a portrait shot stays upright once EXIF is gone.
 */
export async function photoOf(file) {
  const url = URL.createObjectURL(file);
  try {
    const img = new Image();
    img.src = url;
    await img.decode();
    return encodeWithin(img, img.naturalWidth, img.naturalHeight);
  } finally {
    URL.revokeObjectURL(url);
  }
}

/**
 * Photo's properties: `photo`, a data binding to `{image, by}`, empty until someone takes one, and
 * `prompt`, what to photograph. Keep SurfaceValidator.PHOTO (bee-a2ui) in step.
 */
export const PhotoApi = {
  name: 'Photo',
  schema: TextApi.schema
    .omit({ text: true, variant: true })
    .extend({
      photo: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|The photo taken: {image, by}, empty until someone takes one.'),
      prompt: DynamicValueSchema.describe('REF:#/$defs/DynamicValue|What to photograph.').optional(),
    })
    .strict(),
};

const INK = 'var(--ink,#1f2328)';
const MUTED = 'var(--muted,#656d76)';
const LINE = 'var(--line,#d8d6d0)';
const CARD = 'var(--card,#fff)';

const TAKE = 'display:inline-flex;align-items:center;min-height:3rem;padding:0.6rem 1.1rem;cursor:pointer;'
  + `border:1px solid ${LINE};border-radius:10px;background:${CARD};color:${INK};font:inherit;font-size:1.05rem`;
const RETAKE = `cursor:pointer;color:${MUTED};font-size:0.85rem;text-decoration:underline`;

/** `<bee-photo>`: the prompt and "Take a photo", or the photo with who took it and "Retake". */
export class PhotoElement extends A2uiLitElement {
  static properties = { state: { state: true } };

  constructor() {
    super();
    this.bound = new BoundData(this, ['photo', 'prompt']);
    /** `null`, `'sending'`, or an error's message. */
    this.state = null;
  }

  createRenderRoot() {
    return this;
  }

  render() {
    if (!this.controller?.props) return nothing;
    const props = this.bound.values();
    const taken = props.photo && typeof props.photo === 'object' && showable(props.photo.image) ? props.photo : null;
    const prompt = props.prompt == null ? '' : String(props.prompt);
    return html`<div class="bee-photo" style="color:${INK}">
      ${prompt ? html`<p class="prompt" style="margin:0 0 0.75rem;font-size:1.15rem;font-weight:600">${prompt}</p>` : nothing}
      ${taken ? this.shown(taken) : this.picker('take', 'Take a photo', TAKE)}
      ${this.state === 'sending' ? html`<p class="sending" style="margin:0.5rem 0 0;color:${MUTED}">Sending…</p>` : nothing}
      ${this.state && this.state !== 'sending' ? html`<p class="problem" role="alert" style="margin:0.5rem 0 0">${this.state}</p>` : nothing}
    </div>`;
  }

  shown(taken) {
    return html`<img src=${taken.image} alt=${`Photo by ${peerName(taken.by ?? '')}`}
        style="display:block;max-width:100%;height:auto;border-radius:8px">
      <p style="display:flex;justify-content:space-between;margin:0.4rem 0 0;font-size:0.85rem;color:${MUTED}">
        <span class="by">${taken.by ? peerName(taken.by) : ''}</span>
        ${this.picker('retake', 'Retake', RETAKE)}
      </p>`;
  }

  /** A label that opens the camera: the file input inside it does, so no script has to click it. */
  picker(name, label, style) {
    return html`<label class=${name} style=${style}>${label}<input type="file" accept="image/*" capture="environment"
        style="display:none" @change=${(event) => this.taken(event.target)}></label>`;
  }

  async taken(input) {
    const file = input.files?.[0];
    input.value = '';
    if (!file) return;
    this.state = 'sending';
    try {
      const image = await photoOf(file);
      this.context?.dispatchAction({ event: { name: TAKE_ACTION, context: { image } } });
      this.state = null;
    } catch (error) {
      this.state = error?.message || "Couldn't read that photo.";
    }
  }
}
PhotoElement.tagName = 'bee-photo';

/** The catalog entry: Photo's API with its element, as the basic catalog's entries are built. */
export const Photo = { ...PhotoApi, tagName: PhotoElement.tagName, element: PhotoElement };
