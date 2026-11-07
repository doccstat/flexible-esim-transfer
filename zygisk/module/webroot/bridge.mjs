import { t } from './strings.mjs';
const actions = new Set(['status', 'inventory', 'save', 'off', 'start', 'stop', 'delete', 'review', 'export']);
let sequence = 0;
export function command(action, payload = {}) {
  if (!actions.has(action)) throw new Error(t('unknownAction'));
  const bytes = new TextEncoder().encode(JSON.stringify(payload));
  if (bytes.length > 4096) throw new Error(t('requestLarge'));
  const encoded = btoa(String.fromCharCode(...bytes));
  return `printf '%s' '${encoded}' | /system/bin/toybox base64 -d | /system/bin/sh /data/adb/modules/flexible_esim_transfer/ctl.sh ${action}`;
}
export function call(action, payload = {}, scope = globalThis) {
  return new Promise((resolve, reject) => {
    if (typeof scope.ksu?.exec !== 'function') return reject(new Error(t('manager')));
    const callback = `fet_result_${++sequence}`;
    const timer = setTimeout(() => { delete scope[callback]; reject(new Error(t('timeout'))); }, 20000);
    scope[callback] = (code, stdout) => {
      clearTimeout(timer); delete scope[callback];
      try {
        const result = JSON.parse(stdout);
        if (code !== 0 || result.ok !== true) throw new Error(t('unavailable'));
        resolve(result);
      } catch (error) { reject(error); }
    };
    try { scope.ksu.exec(command(action, payload), '{}', callback); }
    catch (error) { clearTimeout(timer); delete scope[callback]; reject(error); }
  });
}
