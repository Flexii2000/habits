// Fotos werden vor dem Hochladen im Browser verkleinert (hoechstens 2048 px an
// der langen Kante, JPEG) - das spart Datenvolumen und bleibt sicher unter den
// 10 MB des Dienstes. Ueber ein <img> gezeichnet, damit der Browser die
// EXIF-Drehung schon angewandt hat: der Dienst dreht nicht (Vertrag 1.1), und
// das neu kodierte JPEG traegt keine Metadaten mehr.
import { api } from './api.js';

async function decode(file) {
    const url = URL.createObjectURL(file);
    const img = new Image();
    img.decoding = 'async';
    img.src = url;
    try {
        await img.decode();
    } catch (err) {
        URL.revokeObjectURL(url);
        throw new Error('Dieses Bild lässt sich nicht öffnen.');
    }
    return { img, url };
}

function toJpeg(canvas, quality) {
    return new Promise((resolve, reject) => {
        canvas.toBlob(blob => (blob ? resolve(blob) : reject(new Error('Das Bild ließ sich nicht umwandeln.'))), 'image/jpeg', quality);
    });
}

export async function resizeImage(file, maxEdge = 2048, quality = 0.85) {
    const { img, url } = await decode(file);
    try {
        const width = img.naturalWidth;
        const height = img.naturalHeight;
        const scale = Math.min(1, maxEdge / Math.max(width, height));
        const canvas = document.createElement('canvas');
        canvas.width = Math.max(1, Math.round(width * scale));
        canvas.height = Math.max(1, Math.round(height * scale));
        const ctx = canvas.getContext('2d');
        // Transparente PNGs bekaemen sonst einen schwarzen Grund.
        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, canvas.width, canvas.height);
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
        return await toJpeg(canvas, quality);
    } finally {
        URL.revokeObjectURL(url);
    }
}

/** Quadratischer Ausschnitt aus der Mitte - fuer den Avatar (Vertrag 3.2). */
export async function squareImage(file, size = 1024, quality = 0.85) {
    const { img, url } = await decode(file);
    try {
        const side = Math.min(img.naturalWidth, img.naturalHeight);
        const sx = (img.naturalWidth - side) / 2;
        const sy = (img.naturalHeight - side) / 2;
        const out = Math.min(size, side);
        const canvas = document.createElement('canvas');
        canvas.width = out;
        canvas.height = out;
        const ctx = canvas.getContext('2d');
        ctx.fillStyle = '#FFFFFF';
        ctx.fillRect(0, 0, out, out);
        ctx.imageSmoothingQuality = 'high';
        ctx.drawImage(img, sx, sy, side, side, 0, 0, out, out);
        return await toJpeg(canvas, quality);
    } finally {
        URL.revokeObjectURL(url);
    }
}

function photoForm(blob) {
    const form = new FormData();
    form.append('photo', blob, 'photo.jpg');
    return form;
}

/** Laedt ein Foto hoch; derselbe Schluessel noch einmal legt nichts doppelt an. */
export function uploadPhoto(blob, key) {
    return api('/photos', { method: 'POST', form: photoForm(blob), headers: { 'Idempotency-Key': key } });
}

export function uploadAvatar(blob) {
    return api('/me/avatar', { method: 'PUT', form: photoForm(blob) });
}

/** Unsichtbares Datei-Feld; `capture` oeffnet auf dem Handy direkt die Kamera. */
export async function pickFile({ capture } = {}) {
    const files = await pickFiles({ capture });
    return files[0] || null;
}

/** Wie `pickFile`, mit `multiple` mehrere auf einmal (die Galerie). */
export function pickFiles({ capture, multiple = false } = {}) {
    return new Promise(resolve => {
        const input = document.createElement('input');
        input.type = 'file';
        input.accept = 'image/*';
        input.multiple = multiple;
        if (capture) input.setAttribute('capture', capture);
        input.style.display = 'none';
        document.body.append(input);
        input.addEventListener('change', () => {
            const files = Array.from(input.files || []);
            input.remove();
            resolve(files);
        }, { once: true });
        // Abbrechen meldet kein Ereignis zuverlaessig; das Feld bleibt dann
        // unsichtbar liegen, bis die naechste Auswahl es ersetzt.
        input.click();
    });
}
