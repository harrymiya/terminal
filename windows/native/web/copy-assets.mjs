import { copyFile, mkdir } from 'node:fs/promises';

const target = new URL('../www/', import.meta.url);
await mkdir(target, { recursive: true });
await copyFile(new URL('./node_modules/@xterm/xterm/css/xterm.css', import.meta.url), new URL('xterm.css', target));
