// QR-Code-Erzeuger ohne Bibliothek (Byte-Modus, Fehlerkorrektur M, Versionen
// 1-40, Maske nach den Strafpunkten der Norm ISO/IEC 18004). Reicht fuer die
// App-Links; Ausgabe als SVG-Pfad, der in jeder Groesse scharf bleibt.

const ECC_CODEWORDS_PER_BLOCK = [
    // L, M, Q, H - Index 0 unbenutzt
    [-1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18, 20, 24, 26, 30, 22, 24, 28, 30, 28, 28, 28, 28, 30, 30, 26, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
    [-1, 10, 16, 26, 18, 24, 16, 18, 22, 22, 26, 30, 22, 22, 24, 24, 28, 28, 26, 26, 26, 26, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28, 28],
    [-1, 13, 22, 18, 26, 18, 24, 18, 22, 20, 24, 28, 26, 24, 20, 30, 24, 28, 28, 26, 30, 28, 30, 30, 30, 30, 28, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
    [-1, 17, 28, 22, 16, 22, 28, 26, 26, 24, 28, 24, 28, 22, 24, 24, 30, 28, 28, 26, 28, 30, 24, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30, 30],
];

const NUM_ERROR_CORRECTION_BLOCKS = [
    [-1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4, 4, 4, 4, 4, 6, 6, 6, 6, 7, 8, 8, 9, 9, 10, 12, 12, 12, 13, 14, 15, 16, 17, 18, 19, 19, 20, 21, 22, 24, 25],
    [-1, 1, 1, 1, 2, 2, 4, 4, 4, 5, 5, 5, 8, 9, 9, 10, 10, 11, 13, 14, 16, 17, 17, 18, 20, 21, 23, 25, 26, 28, 29, 31, 33, 35, 37, 38, 40, 43, 45, 47, 49],
    [-1, 1, 1, 2, 2, 4, 4, 6, 6, 8, 8, 8, 10, 12, 16, 12, 17, 16, 18, 21, 20, 23, 23, 25, 27, 29, 34, 34, 35, 38, 40, 43, 45, 48, 51, 53, 56, 59, 62, 65, 68],
    [-1, 1, 1, 2, 4, 4, 4, 5, 6, 8, 8, 11, 11, 16, 16, 18, 16, 19, 21, 25, 25, 25, 34, 30, 32, 35, 37, 40, 42, 45, 48, 51, 54, 57, 60, 63, 66, 70, 74, 77, 81],
];

const ECL = { L: { ordinal: 0, bits: 1 }, M: { ordinal: 1, bits: 0 }, Q: { ordinal: 2, bits: 3 }, H: { ordinal: 3, bits: 2 } };

function rawDataModules(ver) {
    let result = (16 * ver + 128) * ver + 64;
    if (ver >= 2) {
        const numAlign = Math.floor(ver / 7) + 2;
        result -= (25 * numAlign - 10) * numAlign - 55;
        if (ver >= 7) result -= 36;
    }
    return result;
}

function dataCodewords(ver, ecl) {
    return Math.floor(rawDataModules(ver) / 8)
        - ECC_CODEWORDS_PER_BLOCK[ecl.ordinal][ver] * NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal][ver];
}

// --- Reed-Solomon ueber GF(2^8), Polynom 0x11D ---------------------------------

function gfMultiply(x, y) {
    let z = 0;
    for (let i = 7; i >= 0; i--) {
        z = (z << 1) ^ ((z >>> 7) * 0x11D);
        z ^= ((y >>> i) & 1) * x;
    }
    return z & 0xFF;
}

function rsDivisor(degree) {
    const result = new Array(degree).fill(0);
    result[degree - 1] = 1;
    let root = 1;
    for (let i = 0; i < degree; i++) {
        for (let j = 0; j < result.length; j++) {
            result[j] = gfMultiply(result[j], root);
            if (j + 1 < result.length) result[j] ^= result[j + 1];
        }
        root = gfMultiply(root, 0x02);
    }
    return result;
}

function rsRemainder(data, divisor) {
    const result = divisor.map(() => 0);
    for (const b of data) {
        const factor = b ^ result.shift();
        result.push(0);
        divisor.forEach((coef, i) => { result[i] ^= gfMultiply(coef, factor); });
    }
    return result;
}

// --- Kodieren ------------------------------------------------------------------

function encodeData(bytes, ver, ecl) {
    const bits = [];
    const push = (value, length) => {
        for (let i = length - 1; i >= 0; i--) bits.push((value >>> i) & 1);
    };
    push(0b0100, 4);
    push(bytes.length, ver <= 9 ? 8 : 16);
    bytes.forEach(b => push(b, 8));
    const capacityBits = dataCodewords(ver, ecl) * 8;
    push(0, Math.min(4, capacityBits - bits.length));
    push(0, (8 - (bits.length % 8)) % 8);
    for (let pad = 0xEC; bits.length < capacityBits; pad ^= 0xEC ^ 0x11) push(pad, 8);
    const codewords = [];
    for (let i = 0; i < bits.length; i += 8) {
        let byte = 0;
        for (let j = 0; j < 8; j++) byte = (byte << 1) | bits[i + j];
        codewords.push(byte);
    }
    return codewords;
}

function addEccAndInterleave(data, ver, ecl) {
    const numBlocks = NUM_ERROR_CORRECTION_BLOCKS[ecl.ordinal][ver];
    const blockEccLen = ECC_CODEWORDS_PER_BLOCK[ecl.ordinal][ver];
    const rawCodewords = Math.floor(rawDataModules(ver) / 8);
    const numShortBlocks = numBlocks - (rawCodewords % numBlocks);
    const shortBlockLen = Math.floor(rawCodewords / numBlocks);
    const divisor = rsDivisor(blockEccLen);
    const blocks = [];
    for (let i = 0, k = 0; i < numBlocks; i++) {
        const dat = data.slice(k, k + shortBlockLen - blockEccLen + (i < numShortBlocks ? 0 : 1));
        k += dat.length;
        const ecc = rsRemainder(dat, divisor);
        if (i < numShortBlocks) dat.push(0);
        blocks.push(dat.concat(ecc));
    }
    const result = [];
    for (let i = 0; i < blocks[0].length; i++) {
        blocks.forEach((block, j) => {
            if (i !== shortBlockLen - blockEccLen || j >= numShortBlocks) result.push(block[i]);
        });
    }
    return result;
}

function alignmentPositions(ver, size) {
    if (ver === 1) return [];
    const numAlign = Math.floor(ver / 7) + 2;
    const step = ver === 32 ? 26 : Math.ceil((ver * 4 + 4) / (numAlign * 2 - 2)) * 2;
    const result = [6];
    for (let pos = size - 7; result.length < numAlign; pos -= step) result.splice(1, 0, pos);
    return result;
}

class Matrix {
    constructor(ver, ecl) {
        this.ver = ver;
        this.ecl = ecl;
        this.size = ver * 4 + 17;
        this.modules = Array.from({ length: this.size }, () => new Array(this.size).fill(false));
        this.isFunction = Array.from({ length: this.size }, () => new Array(this.size).fill(false));
    }

    set(x, y, dark) {
        this.modules[y][x] = dark;
        this.isFunction[y][x] = true;
    }

    drawFunctionPatterns() {
        const size = this.size;
        for (let i = 0; i < size; i++) {
            this.set(6, i, i % 2 === 0);
            this.set(i, 6, i % 2 === 0);
        }
        this.drawFinder(3, 3);
        this.drawFinder(size - 4, 3);
        this.drawFinder(3, size - 4);
        const align = alignmentPositions(this.ver, size);
        const n = align.length;
        for (let i = 0; i < n; i++) {
            for (let j = 0; j < n; j++) {
                if ((i === 0 && j === 0) || (i === 0 && j === n - 1) || (i === n - 1 && j === 0)) continue;
                this.drawAlignment(align[i], align[j]);
            }
        }
        this.drawFormatBits(0);
        this.drawVersion();
    }

    drawFinder(x, y) {
        for (let dy = -4; dy <= 4; dy++) {
            for (let dx = -4; dx <= 4; dx++) {
                const dist = Math.max(Math.abs(dx), Math.abs(dy));
                const xx = x + dx;
                const yy = y + dy;
                if (xx >= 0 && xx < this.size && yy >= 0 && yy < this.size) this.set(xx, yy, dist !== 2 && dist !== 4);
            }
        }
    }

    drawAlignment(x, y) {
        for (let dy = -2; dy <= 2; dy++) {
            for (let dx = -2; dx <= 2; dx++) this.set(x + dx, y + dy, Math.max(Math.abs(dx), Math.abs(dy)) !== 1);
        }
    }

    drawFormatBits(mask) {
        const data = (this.ecl.bits << 3) | mask;
        let rem = data;
        for (let i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        const bits = ((data << 10) | rem) ^ 0x5412;
        const bit = i => ((bits >>> i) & 1) !== 0;
        for (let i = 0; i <= 5; i++) this.set(8, i, bit(i));
        this.set(8, 7, bit(6));
        this.set(8, 8, bit(7));
        this.set(7, 8, bit(8));
        for (let i = 9; i < 15; i++) this.set(14 - i, 8, bit(i));
        const size = this.size;
        for (let i = 0; i < 8; i++) this.set(size - 1 - i, 8, bit(i));
        for (let i = 8; i < 15; i++) this.set(8, size - 15 + i, bit(i));
        this.set(8, size - 8, true);
    }

    drawVersion() {
        if (this.ver < 7) return;
        let rem = this.ver;
        for (let i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
        const bits = (this.ver << 12) | rem;
        for (let i = 0; i < 18; i++) {
            const dark = ((bits >>> i) & 1) !== 0;
            const a = this.size - 11 + (i % 3);
            const b = Math.floor(i / 3);
            this.set(a, b, dark);
            this.set(b, a, dark);
        }
    }

    drawCodewords(data) {
        const size = this.size;
        let i = 0;
        for (let right = size - 1; right >= 1; right -= 2) {
            if (right === 6) right = 5;
            for (let vert = 0; vert < size; vert++) {
                for (let j = 0; j < 2; j++) {
                    const x = right - j;
                    const upward = ((right + 1) & 2) === 0;
                    const y = upward ? size - 1 - vert : vert;
                    if (!this.isFunction[y][x] && i < data.length * 8) {
                        this.modules[y][x] = ((data[i >>> 3] >>> (7 - (i & 7))) & 1) !== 0;
                        i++;
                    }
                }
            }
        }
    }

    applyMask(mask) {
        for (let y = 0; y < this.size; y++) {
            for (let x = 0; x < this.size; x++) {
                if (this.isFunction[y][x]) continue;
                let invert;
                switch (mask) {
                    case 0: invert = (x + y) % 2 === 0; break;
                    case 1: invert = y % 2 === 0; break;
                    case 2: invert = x % 3 === 0; break;
                    case 3: invert = (x + y) % 3 === 0; break;
                    case 4: invert = (Math.floor(x / 3) + Math.floor(y / 2)) % 2 === 0; break;
                    case 5: invert = ((x * y) % 2) + ((x * y) % 3) === 0; break;
                    case 6: invert = (((x * y) % 2) + ((x * y) % 3)) % 2 === 0; break;
                    default: invert = (((x + y) % 2) + ((x * y) % 3)) % 2 === 0; break;
                }
                if (invert) this.modules[y][x] = !this.modules[y][x];
            }
        }
    }

    penalty() {
        const size = this.size;
        const m = this.modules;
        let score = 0;
        // Regel 1: Laeufe von fuenf und mehr gleichen Modulen, Zeilen und Spalten.
        for (let pass = 0; pass < 2; pass++) {
            for (let a = 0; a < size; a++) {
                let run = 1;
                for (let b = 1; b < size; b++) {
                    const cur = pass === 0 ? m[a][b] : m[b][a];
                    const prev = pass === 0 ? m[a][b - 1] : m[b - 1][a];
                    if (cur === prev) {
                        run++;
                        if (run === 5) score += 3;
                        else if (run > 5) score += 1;
                    } else {
                        run = 1;
                    }
                }
            }
        }
        // Regel 2: 2x2-Bloecke einer Farbe.
        for (let y = 0; y < size - 1; y++) {
            for (let x = 0; x < size - 1; x++) {
                const c = m[y][x];
                if (c === m[y][x + 1] && c === m[y + 1][x] && c === m[y + 1][x + 1]) score += 3;
            }
        }
        // Regel 3: Muster, die wie ein Suchmuster aussehen (1:1:3:1:1 mit Rand).
        const patterns = [
            [true, false, true, true, true, false, true, false, false, false, false],
            [false, false, false, false, true, false, true, true, true, false, true],
        ];
        for (let a = 0; a < size; a++) {
            for (let b = 0; b <= size - 11; b++) {
                for (const pattern of patterns) {
                    let rowHit = true;
                    let colHit = true;
                    for (let k = 0; k < 11; k++) {
                        if (m[a][b + k] !== pattern[k]) rowHit = false;
                        if (m[b + k][a] !== pattern[k]) colHit = false;
                    }
                    if (rowHit) score += 40;
                    if (colHit) score += 40;
                }
            }
        }
        // Regel 4: Anteil dunkler Module nahe 50 %.
        let dark = 0;
        m.forEach(row => row.forEach(v => { if (v) dark++; }));
        const total = size * size;
        const k = Math.ceil(Math.abs(dark * 20 - total * 10) / total) - 1;
        score += Math.max(0, k) * 10;
        return score;
    }
}

export function encodeQr(text, eclName = 'M') {
    const bytes = [...new TextEncoder().encode(text)];
    const ecl = ECL[eclName];
    let ver = 1;
    for (; ver <= 40; ver++) {
        const needBits = 4 + (ver <= 9 ? 8 : 16) + bytes.length * 8;
        if (needBits <= dataCodewords(ver, ecl) * 8) break;
    }
    if (ver > 40) throw new Error('Text zu lang für einen QR-Code.');
    const data = addEccAndInterleave(encodeData(bytes, ver, ecl), ver, ecl);
    const matrix = new Matrix(ver, ecl);
    matrix.drawFunctionPatterns();
    matrix.drawCodewords(data);
    let best = 0;
    let bestScore = Infinity;
    for (let mask = 0; mask < 8; mask++) {
        matrix.applyMask(mask);
        matrix.drawFormatBits(mask);
        const score = matrix.penalty();
        if (score < bestScore) {
            best = mask;
            bestScore = score;
        }
        matrix.applyMask(mask);
    }
    matrix.applyMask(best);
    matrix.drawFormatBits(best);
    return matrix.modules;
}

/** SVG mit vier Modulen Ruhezone; dunkle Module als ein einziger Pfad. */
export function qrSvg(text, { dark = '#1C1B2E', light = '#FFFFFF' } = {}) {
    const modules = encodeQr(text);
    const size = modules.length;
    const border = 4;
    const dim = size + border * 2;
    let path = '';
    for (let y = 0; y < size; y++) {
        for (let x = 0; x < size; x++) {
            if (modules[y][x]) path += `M${x + border},${y + border}h1v1h-1z`;
        }
    }
    return `<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 ${dim} ${dim}" shape-rendering="crispEdges" role="img" aria-label="QR-Code">`
        + `<rect width="100%" height="100%" fill="${light}"/><path d="${path}" fill="${dark}"/></svg>`;
}
