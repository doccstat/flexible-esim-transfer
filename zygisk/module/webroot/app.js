import { call } from './bridge.mjs';
import { t } from './strings.mjs';
const $ = id => document.getElementById(id);
for (const node of document.querySelectorAll('[data-text]')) node.textContent = t(node.dataset.text);
for (const node of document.querySelectorAll('[data-minutes]')) node.textContent = t('minutes', { count: new Intl.NumberFormat('en').format(Number(node.dataset.minutes)) });
$('port').placeholder = t('portHint');
$('report').setAttribute('aria-label', t('report'));
let choice = null, busy = false, generation = 0;
function message(text) { $('message').textContent = text; }
async function status(updateOption = false) {
  const state = await call('status');
  $('status').textContent = t('status', {version: state.version, routing: t(state.routing ? 'on' : 'disabled'), recording: t(state.recording ? (state.readerReady ? 'recording' : 'starting') : state.recordResult || 'idle')});
  if (updateOption) $('preserve').checked = state.preserve;
  $('start').disabled = state.recording || state.report;
  $('stop').disabled = !state.recording;
  $('delete').disabled = state.recording || !state.report;
  $('export').disabled = state.recording || !state.report;
  $('review').disabled = !state.report;
}
async function run(action) {
  if (busy) return;
  busy = true;
  try { await action(); } catch (error) { message(error.message); }
  finally { busy = false; }
}
function clearCards() {
  choice = null; $('cards').replaceChildren();
  const legend = document.createElement('legend'); legend.textContent = t('choose'); $('cards').append(legend);
}
async function refresh() {
  clearCards(); const current = ++generation;
  const { cards } = await call('inventory', { reveal: $('reveal').checked });
  if (current !== generation || document.hidden) return;
  for (const card of cards) {
    const title = document.createElement('p');
    title.textContent = t('card', {kind: t(card.removable ? 'removable' : 'builtIn'), slot: card.slot, eid: card.eid});
    $('cards').append(title);
    for (const port of card.ports) {
      const label = document.createElement('label'), radio = document.createElement('input');
      radio.type = 'radio'; radio.name = 'destination'; radio.disabled = !port.active || port.logicalSlot < 0 || !/^[a-f0-9]{64}$/.test(card.fingerprint);
      radio.addEventListener('change', () => { choice = { fingerprint: card.fingerprint, port: String(port.index) }; $('manual').checked = false; $('manualFields').hidden = true; });
      label.append(radio, t('cardPort', {port: port.index, slot: port.logicalSlot, state: t(port.active ? 'active' : 'inactive')})); $('cards').append(label);
    }
  }
  message(t(cards.length ? 'selectHint' : 'noCards'));
}
$('manual').addEventListener('change', () => { $('manualFields').hidden = !$('manual').checked; choice = null; for (const input of $('cards').querySelectorAll('input')) input.checked = false; });
$('refresh').onclick = () => run(refresh);
$('reveal').onchange = () => run(refresh);
$('save').onclick = () => run(async () => {
  const manual = $('manual').checked;
  if (!manual && !choice) throw new Error(t('select'));
  const payload = { manual, fingerprint: choice?.fingerprint || '', port: manual ? $('port').value : choice.port, eid: manual ? $('eid').value : '', preserve: $('preserve').checked, confirmUnverified: manual };
  if (manual && !/^[0-9]{32}$/.test(payload.eid)) throw new Error(t('invalidEid'));
  if (!/^[0-9]*$/.test(payload.port)) throw new Error(t('invalidPort'));
  if (!confirm(t('confirmSave', {manual: manual ? t('unverified') : '', preserve: t(payload.preserve ? 'on' : 'disabled')}))) return;
  await call('save', payload); $('eid').value = ''; await status(); message(t('saved'));
});
$('off').onclick = () => run(async () => {
  if (!confirm(t('confirmOff'))) return;
  await call('off'); $('eid').value = ''; $('port').value = ''; clearCards(); await status(true); message(t('offSaved'));
});
$('start').onclick = () => run(async () => {
  const seconds = Number($('duration').value);
  if (![300, 900, 1800, 3600].includes(seconds)) throw new Error(t('invalidDuration'));
  const result = await call('start', { seconds }); await status(); message(t(result.readerReady ? 'started' : 'startPending'));
});
$('stop').onclick = () => run(async () => { await call('stop'); message(t('stopRequested')); await status(); });
$('review').onclick = () => run(async () => { $('report').textContent = (await call('review')).report; });
$('delete').onclick = () => run(async () => { if (!confirm(t('confirmDelete'))) return; await call('delete'); $('report').textContent = ''; await status(); message(t('deleted')); });
$('export').onclick = () => run(async () => {
  const name = prompt(t('filename'));
  if (name === null) return;
  const result = await call('export', { name }); message(t('exported', {path: result.exported}));
});
document.addEventListener('visibilitychange', () => {
  if (document.hidden) { ++generation; clearCards(); $('eid').value = ''; $('reveal').checked = false; $('report').textContent = ''; }
  else run(status);
});
setInterval(() => { if (!document.hidden && !busy) run(status); }, 4000);
run(() => status(true));
