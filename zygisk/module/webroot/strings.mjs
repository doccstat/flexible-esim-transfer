// English source and fallback; no other UI locales have been selected.
export const en = Object.freeze({
  title: 'Flexible eSIM Transfer', loading: 'Loading…',
  risk: 'Transfers can leave you without access to your eSIM or cellular service, even if a download fails. Carrier reissue may be needed. Test a recoverable, nonessential line first. You are responsible for testing and recovery; this software is provided without warranty.',
  activation: 'Use one backend. Disable this module’s Vector version before activating Zygisk. Settings changes require your device’s supported restart procedure.',
  destination: 'Destination', refresh: 'Refresh cards', reveal: 'Reveal EIDs',
  choose: 'Choose a real card and active port', manual: 'Enter EID manually', eid: 'EID', port: 'Port',
  portHint: 'Empty: only active port', preserve: 'Preserve card session',
  preserveHelp: 'Preserves the selected card’s native channel across metadata and download. Phone hooks are required. Restarting Phone interrupts cellular service.',
  save: 'Route new downloads here', off: 'Off / forget destination', diagnostics: 'Diagnostics',
  diagnosticsHelp: 'Private, redacted recording; maximum 1 MiB. Never resumes or uploads automatically. Closing this page does not stop a running timed recording.',
  duration: 'Duration', chooseDuration: 'Choose…', minutes: '{count} minutes', start: 'Start', stop: 'Stop',
  review: 'Review', export: 'Export to Downloads', delete: 'Delete report', report: 'Redacted diagnostic report',
  status: '{version} · Routing {routing} · {recording}', on: 'on', disabled: 'off',
  recording: 'Recording redacted events', starting: 'Starting reader', idle: 'Not recording',
  STOPPED: 'Stopped', DURATION_REACHED: 'Time limit reached', LIMIT_REACHED: 'Size limit reached',
  READER_UNAVAILABLE: 'Reader unavailable', READ_FAILED: 'Capture failed', STORAGE_FAILED: 'Storage failed',
  removable: 'Removable', builtIn: 'Built-in', active: 'active', inactive: 'inactive',
  card: '{kind} · Physical slot {slot} · EID {eid}',
  cardPort: ' Port {port} → logical slot {slot} ({state})',
  selectHint: 'Select a destination. Physical slot and port are different.',
  noCards: 'No readable eUICCs. Manual entry is available.', select: 'Select a card and port.',
  invalidEid: 'Enter the exact 32-digit EID.',
  invalidPort: 'Use the exact non-negative port number, or leave empty for the only active port.',
  confirmSave: 'Route ALL new SIM Manager downloads here, including QR downloads?\n{manual}Preserve card session: {preserve}.\nA supported restart is required; do not enable both backends.',
  unverified: 'Manual selection cannot be verified here. ', saved: 'Saved. Restart using your device’s supported procedure.',
  confirmOff: 'Forget this backend’s destination and disable session preservation? This does not undo a transfer or unload existing hooks. A supported restart is required.',
  offSaved: 'Routing off saved. Restart required.', invalidDuration: 'Choose a recording duration.',
  stopRequested: 'Stop requested.', confirmDelete: 'Permanently delete the private diagnostic report?',
  deleted: 'Report deleted.', filename: 'Filename in Downloads (used exactly as entered, including extension). Existing files will not be replaced.',
  exported: 'Exported to {path}.', unknownAction: 'Unknown action', requestLarge: 'Request too large',
  manager: 'Open in KernelSU Manager.', timeout: 'Request timed out; refresh status before retrying.',
  unavailable: 'Operation unavailable. Check module activation, selection and existing report before retrying.',
});
export function t(key, values = {}) {
  if (!Object.hasOwn(en, key)) throw new Error('Unknown UI text key');
  return en[key].replace(/\{(\w+)\}/g, (_, name) => {
    if (!Object.hasOwn(values, name)) throw new Error('Missing UI text placeholder');
    return String(values[name]);
  });
}
