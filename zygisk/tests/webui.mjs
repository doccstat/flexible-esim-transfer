import assert from 'node:assert/strict';
import { command, call } from '../module/webroot/bridge.mjs';
import { en, t } from '../module/webroot/strings.mjs';
import { readFileSync } from 'node:fs';
import { runInNewContext } from 'node:vm';
const html = readFileSync(new URL('../module/webroot/index.html', import.meta.url), 'utf8');
for (const [, key] of html.matchAll(/data-text="([^"]+)"/g)) assert.equal(typeof en[key], 'string');
assert.match(t('risk'), /without access to your eSIM/);
assert.equal(t('exported', {path: 'Download/unchanged名.jsonl'}), 'Exported to Download/unchanged名.jsonl.');
assert.throws(() => t('exported'));
assert.throws(() => t('unknown-key'));
const malicious = "'; touch /data/local/tmp/unwanted; echo '";
const value = command('save', {eid: malicious, port: '00'});
assert(!value.includes(malicious));
const base64 = value.match(/printf '%s' '([A-Za-z0-9+/=]+)'/)[1];
assert.deepEqual(JSON.parse(Buffer.from(base64, 'base64').toString()), {eid: malicious, port: '00'});
assert.throws(() => command('status; reboot'));
assert.throws(() => command('save', {eid: 'a'.repeat(5000)}));
const scope = {ksu: {exec: (cmd, options, callback) => scope[callback](0, '{"ok":true}')}};
assert.equal((await call('status', {}, scope)).ok, true);
assert.deepEqual(Object.keys(scope), ['ksu']);
await assert.rejects(call('status', {}, {}));
// Exercise the real handlers with only the DOM and root bridge replaced.
const nodes = new Map(), listeners = new Map(), requests = [];
const node = () => ({value: '300', textContent: 'previous notice', checked: false,
  setAttribute() {}, addEventListener() {}, replaceChildren() {}, append() {}, querySelectorAll: () => []});
const get = id => { if (!nodes.has(id)) nodes.set(id, node()); return nodes.get(id); };
const document = {hidden: false, getElementById: get, createElement: node,
  querySelectorAll: () => [], addEventListener: (name, callback) => listeners.set(name, callback)};
const state = {version: 'fixture', routing: true, preserve: true, recording: false, readerReady: false, report: false};
let poll;
const app = readFileSync(new URL('../module/webroot/app.js', import.meta.url), 'utf8');
runInNewContext(app.replace(/^import .*;\n/gm, ''), {document, t, Intl,
  setInterval: callback => { poll = callback; },
  call: async (action, payload) => {
    requests.push(action);
    if (action === 'start') {
      assert.equal(payload.seconds, 300);
      state.recording = state.report = true;
      return {recording: true, readerReady: false};
    }
    assert.equal(action, 'status');
    return {...state};
  }});
const turn = () => new Promise(resolve => setImmediate(resolve));
await turn();
await get('start').onclick();
assert.match(get('status').textContent, /Starting reader/);
assert.equal(get('message').textContent, '');
state.readerReady = true;
poll(); await turn();
assert.match(get('status').textContent, /Recording redacted events/);
assert.equal(get('message').textContent, '');
const beforeHide = requests.length;
document.hidden = true; listeners.get('visibilitychange')(); poll(); await turn();
assert.equal(requests.length, beforeHide);
assert(state.recording);
document.hidden = false; listeners.get('visibilitychange')(); await turn();
assert.match(get('status').textContent, /Recording redacted events/);
state.recording = state.readerReady = false; state.recordResult = 'READER_UNAVAILABLE';
poll(); await turn();
assert.match(get('status').textContent, /Reader unavailable/);
assert.equal(get('stop').disabled, true);
assert.equal(get('start').disabled, true); // The existing report must not be replaced.
assert.equal(get('export').disabled, false);
assert(!requests.includes('stop'));
console.log('WebUI encoding, readiness, backgrounding and terminal-status checks passed.');
