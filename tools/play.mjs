/**
 * Publishes to Google Play through the Google Play Developer API (docs/play-api.md).
 *
 *   node tools/play.mjs status                                   # the tracks and their releases
 *   node tools/play.mjs listing                                  # the descriptions `--listing` sends
 *   node tools/play.mjs edit --aab release/x.aab --notes "…"     # checks an edit, then throws it away
 *   node tools/play.mjs edit --aab release/x.aab --notes "…" --listing --commit
 *   node tools/play.mjs edit --version-code 5 --track alpha           # a bundle already on Play
 *   node tools/play.mjs edit --track my-track --discard-drafts
 *   node tools/play.mjs data-safety docs/play-data-safety.csv
 *
 * `edit` puts an AAB on a track (`--track`, internal by default) and, with `--listing`, the store
 * listing's descriptions from docs/play-console.md, in one edit. `--version-code` puts a bundle already
 * on Play on the track instead, with the release notes it has on another track unless `--notes` is
 * given. `--discard-drafts` removes the track's draft releases. Without `--commit` it only asks
 * Play to validate the edit. Committing sends the changes for review, together with any changes
 * made in Play Console that weren't sent yet. `data-safety` replaces the data safety answers with a
 * CSV in Play Console's export format, at once and without an edit.
 *
 * The service account's key is read from $PLAY_SERVICE_ACCOUNT, or
 * ~/.yorisoi-sanpoguide/play-service-account.json. Only Node's standard library.
 */

import { createSign } from 'node:crypto';
import { readFileSync } from 'node:fs';
import { homedir } from 'node:os';
import { join } from 'node:path';
import { parseArgs } from 'node:util';

const PACKAGE = 'io.github.duwenji.yorisoi.sanpoguide';
const LANGUAGE = 'ja-JP';
const API = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE}`;
const UPLOAD = `https://androidpublisher.googleapis.com/upload/androidpublisher/v3/applications/${PACKAGE}`;

const keyPath = process.env.PLAY_SERVICE_ACCOUNT ?? join(homedir(), '.yorisoi-sanpoguide', 'play-service-account.json');

const base64url = (s) => Buffer.from(s).toString('base64url');

async function accessToken() {
  const key = JSON.parse(readFileSync(keyPath, 'utf8'));
  const now = Math.floor(Date.now() / 1000);
  const claims = {
    iss: key.client_email,
    scope: 'https://www.googleapis.com/auth/androidpublisher',
    aud: key.token_uri,
    iat: now,
    exp: now + 3600,
  };
  const unsigned = `${base64url(JSON.stringify({ alg: 'RS256', typ: 'JWT' }))}.${base64url(JSON.stringify(claims))}`;
  const signature = createSign('RSA-SHA256').update(unsigned).sign(key.private_key, 'base64url');
  const res = await fetch(key.token_uri, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'urn:ietf:params:oauth:grant-type:jwt-bearer', assertion: `${unsigned}.${signature}` }),
  });
  if (!res.ok) throw new Error(`token: ${res.status} ${await res.text()}`);
  return (await res.json()).access_token;
}

let token;
async function call(method, url, body, contentType = 'application/json') {
  token ??= await accessToken();
  const res = await fetch(url, {
    method,
    headers: { Authorization: `Bearer ${token}`, ...(body !== undefined ? { 'Content-Type': contentType } : {}) },
    body: body === undefined ? undefined : contentType === 'application/json' ? JSON.stringify(body) : body,
  });
  const text = await res.text();
  if (!res.ok) throw new Error(`${method} ${url.replace(API, '').replace(UPLOAD, '')}: ${res.status} ${text}`);
  return text ? JSON.parse(text) : {};
}

/** The descriptions in docs/play-console.md: the text block under each heading. */
function listing() {
  const doc = readFileSync(new URL('../docs/play-console.md', import.meta.url), 'utf8').replace(/\r\n/g, '\n');
  const block = (heading) => {
    const m = doc.match(new RegExp(`^#+ ${heading}\\n\\n\`\`\`text\\n([\\s\\S]*?)\\n\`\`\``, 'm'));
    if (!m) throw new Error(`play-console.md has no text block under the heading "${heading}"`);
    return m[1];
  };
  return { shortDescription: block('簡単な説明'), fullDescription: block('詳しい説明') };
}

async function status() {
  const { id } = await call('POST', `${API}/edits`, {});
  try {
    const { tracks = [] } = await call('GET', `${API}/edits/${id}/tracks`);
    for (const t of tracks) {
      for (const r of t.releases ?? []) console.log(`${t.track}\t${r.name ?? ''}\t${(r.versionCodes ?? []).join(',')}\t${r.status}`);
    }
  } finally {
    await call('DELETE', `${API}/edits/${id}`);
  }
}

/** The release notes of the release that has `versionCode` on any track. */
async function notesOf(id, versionCode) {
  const { tracks = [] } = await call('GET', `${API}/edits/${id}/tracks`);
  for (const t of tracks) {
    for (const r of t.releases ?? []) if ((r.versionCodes ?? []).includes(versionCode) && r.releaseNotes) return r.releaseNotes;
  }
  throw new Error(`no release notes for versionCode ${versionCode}; pass --notes`);
}

async function edit(opts) {
  const { id } = await call('POST', `${API}/edits`, {});
  let committed = false;
  try {
    if (opts.aab && opts['version-code']) throw new Error('pass --aab or --version-code, not both');
    if (opts.aab || opts['version-code']) {
      let versionCode = opts['version-code'];
      if (opts.aab) {
        if (!opts.notes) throw new Error('--notes is needed with --aab');
        const bundle = await call('POST', `${UPLOAD}/edits/${id}/bundles?uploadType=media`, readFileSync(opts.aab), 'application/octet-stream');
        versionCode = String(bundle.versionCode);
        console.log(`bundle: versionCode ${versionCode}`);
      }
      const releaseNotes = opts.notes ? [{ language: LANGUAGE, text: opts.notes }] : await notesOf(id, versionCode);
      await call('PUT', `${API}/edits/${id}/tracks/${opts.track}`, {
        track: opts.track,
        releases: [{ versionCodes: [versionCode], status: 'completed', releaseNotes }],
      });
      console.log(`track: ${opts.track} <- versionCode ${versionCode}`);
      for (const n of releaseNotes) console.log(`notes (${n.language}): ${n.text}`);
    }
    if (opts['discard-drafts']) {
      const track = await call('GET', `${API}/edits/${id}/tracks/${opts.track}`);
      const releases = track.releases ?? [];
      const kept = releases.filter((r) => r.status !== 'draft');
      await call('PUT', `${API}/edits/${id}/tracks/${opts.track}`, { track: opts.track, releases: kept });
      console.log(`track: ${opts.track} (${releases.length - kept.length} drafts discarded)`);
    }
    if (opts.listing) {
      const l = listing();
      await call('PATCH', `${API}/edits/${id}/listings/${LANGUAGE}`, l);
      console.log(`listing: ${LANGUAGE} (${l.shortDescription.length} / ${l.fullDescription.length} characters)`);
    }
    await call('POST', `${API}/edits/${id}:validate`);
    if (!opts.commit) {
      console.log('valid; not committed (pass --commit to send it for review)');
      return;
    }
    await call('POST', `${API}/edits/${id}:commit`);
    committed = true;
    console.log('committed and sent for review');
  } finally {
    if (!committed) await call('DELETE', `${API}/edits/${id}`).catch(() => {});
  }
}

async function dataSafety(csv) {
  await call('POST', `${API}/dataSafety`, { safetyLabels: readFileSync(csv, 'utf8') });
  console.log('data safety: replaced');
}

const { positionals, values } = parseArgs({
  allowPositionals: true,
  options: {
    aab: { type: 'string' },
    'version-code': { type: 'string' },
    notes: { type: 'string' },
    track: { type: 'string', default: 'internal' },
    listing: { type: 'boolean', default: false },
    'discard-drafts': { type: 'boolean', default: false },
    commit: { type: 'boolean', default: false },
  },
});

const [command, arg] = positionals;
try {
  if (command === 'status') await status();
  else if (command === 'listing') console.log(listing());
  else if (command === 'edit') await edit(values);
  else if (command === 'data-safety' && arg) await dataSafety(arg);
  else {
    console.error('usage: node tools/play.mjs status | listing | edit [--aab file --notes text | --version-code n [--notes text]] [--track t] [--discard-drafts] [--listing] [--commit] | data-safety file.csv');
    process.exitCode = 2;
  }
} catch (e) {
  console.error(e.message);
  process.exitCode = 1;
}
