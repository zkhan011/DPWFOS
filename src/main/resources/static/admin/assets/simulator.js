(() => {
  'use strict';
  const root = document.getElementById('sim-console');
  if (!root) return;
  const el = id => document.getElementById(id);
  const mode = el('fms-mode'), url = el('fms-url'), idem = el('fms-idempotency');
  const editor = el('job-payload'), select = el('live-vehicle'), send = el('dispatch-job');
  let snapshot = null, dirty = false, connected = false, sending = false, revision = null;
  const result = text => { el('console-result').textContent = text; };
  async function request(path, data) {
    const options = {credentials: 'same-origin', cache: 'no-store', headers: {'Accept': 'application/json'}};
    if (data) {
      options.method = 'POST';
      options.body = new URLSearchParams({...data, [root.dataset.csrfName]: root.dataset.csrfToken});
    }
    const response = await fetch(root.dataset.base + path, options);
    if (response.redirected) throw new Error('Session expired. Sign in again; copy your draft before refreshing.');
    let body;
    try { body = await response.json(); } catch (_) { throw new Error('Session expired or server unavailable. Copy your draft before refreshing.'); }
    if (!response.ok) throw new Error(body.error || ('Request failed: HTTP ' + response.status));
    return body;
  }
  function table(id, rows, keys, empty) {
    const body = el(id);
    body.replaceChildren();
    if (!rows.length) {
      const tr = document.createElement('tr'), td = document.createElement('td');
      td.colSpan = keys.length; td.textContent = empty; tr.append(td); body.append(tr); return;
    }
    for (const row of rows) {
      const tr = document.createElement('tr');
      for (const key of keys) { const td = document.createElement('td'); td.textContent = row[key] ?? '—'; tr.append(td); }
      body.append(tr);
    }
  }
  function updateGate() {
    let vehicle;
    try { vehicle = JSON.parse(editor.value).vehicleId; } catch (_) { /* backend performs full validation */ }
    const settings = snapshot?.settings;
    const loggedIn = snapshot?.vehicles.some(v => v.vehicle_id === vehicle && v.origin === settings.mode && v.action === 'IN');
    send.disabled = !connected || !snapshot?.enabled || dirty || sending || !loggedIn;
    el('dispatch-hint').textContent = !connected ? 'Live status unavailable. Sending is disabled.'
      : dirty ? 'Save FMS settings before sending.' : !snapshot.enabled ? 'Enable the simulator module first.'
      : !loggedIn ? 'Payload vehicleId must match a vehicle logged in for the selected FMS mode.'
      : `Ready: ${vehicle} → ${settings.mode === 'REAL' ? settings.fms_url : 'simulated FMS'}. Payload is validated again before acceptance.`;
    el('sim-session').hidden = settings?.mode === 'REAL';
    const auto = document.querySelector('form[action$="/admin/simulator/run"] button');
    if (auto) auto.disabled = !connected || settings?.mode === 'REAL';
  }
  async function refresh() {
    try {
      const data = await request('/status');
      if (revision !== null && revision !== data.settings.revision && dirty)
        result('FMS settings changed in another session. Your edits are preserved; saving now will update the latest revision.');
      snapshot = data; revision = data.settings.revision; connected = true;
      if (!dirty) { mode.value = data.settings.mode; url.value = data.settings.fms_url; idem.checked = data.settings.idempotency_confirmed; }
      el('live-status').textContent = 'Live · ' + new Date().toLocaleTimeString();
      el('route-summary').textContent = 'Saved mode: ' + data.settings.mode + (data.settings.mode === 'REAL' ? ' · ' + data.settings.fms_url : ' · loopback mock receiver');
      const previous = select.value;
      select.replaceChildren(new Option('Choose a logged-in vehicle', ''));
      data.vehicles.filter(v => v.origin === data.settings.mode && v.action === 'IN')
        .forEach(v => select.add(new Option(v.vehicle_id + ' · ' + v.user_name, v.vehicle_id)));
      if ([...select.options].some(o => o.value === previous)) select.value = previous;
      table('vehicle-status', data.vehicles.map(v => ({...v, status: v.action === 'IN' ? 'Logged in' : 'Logged out'})),
        ['origin','vehicle_id','status','user_name','event_time'], 'No vehicle sessions received.');
      table('session-events',data.events,['received_at','origin','vehicle_id','action','user_name','event_time'],'Waiting for FMS login/logout events.');
      table('manual-deliveries',data.dispatches,['msg_id','vehicle_id','mode','destination','state','last_outcome'],'No manual jobs submitted.');
    } catch (error) { connected = false; el('live-status').textContent = error.message; }
    updateGate();
  }
  [mode,url,idem].forEach(input => input.addEventListener('input', () => { dirty = true; updateGate(); }));
  editor.addEventListener('input', updateGate);
  el('fms-settings').addEventListener('submit', async event => {
    event.preventDefault();
    try {
      await request('/settings',{mode:mode.value,url:url.value,idempotency:idem.checked,revision});
      dirty = false; result('FMS settings saved. Already queued jobs retain their original destination.'); await refresh();
    } catch (error) { result(error.message); }
  });
  el('apply-vehicle').addEventListener('click', () => {
    try {
      if (!select.value) throw new Error('Choose a logged-in vehicle first.');
      const body = JSON.parse(editor.value); body.vehicleId = select.value;
      editor.value = JSON.stringify(body,null,2); updateGate();
    } catch (error) { result(error.message); }
  });
  async function template() {
    try {
      const vehicle = select.value || (snapshot?.settings.mode === 'REAL' ? '' : el('sim-vehicle').value);
      if (!vehicle) throw new Error('Wait for a real FMS login and select a vehicle first.');
      const body = await request('/template?vehicle=' + encodeURIComponent(vehicle));
      editor.value = body.payload; result('New message ID and timestamp generated. Edit and validate before sending.'); updateGate();
    } catch (error) { result(error.message); }
  }
  el('new-job').addEventListener('click', () => {
    if (!editor.value || window.confirm('Replace the current draft with a new job template?')) template();
  });
  el('format-job').addEventListener('click', () => {
    try { editor.value = JSON.stringify(JSON.parse(editor.value),null,2); result('JSON formatted.'); }
    catch (error) { result('Invalid JSON: ' + error.message); }
    updateGate();
  });
  el('validate-job').addEventListener('click', async () => {
    try { const v = await request('/validate',{payload:editor.value}); result('Valid contract · vehicle ' + v.vehicleId + ' · message ' + v.msgId); }
    catch (error) { result(error.message); }
  });
  el('sim-session').addEventListener('submit', async event => {
    event.preventDefault();
    try { await request('/session',{vehicle:el('sim-vehicle').value,action:event.submitter.value}); result('Simulated session event accepted.'); await refresh(); }
    catch (error) { result(error.message); }
  });
  el('job-editor').addEventListener('submit', async event => {
    event.preventDefault();
    if (send.disabled) return;
    if (snapshot.settings.mode === 'REAL' && !window.confirm('Send this job through FOS to the real FMS at ' + snapshot.settings.fms_url + '?')) return;
    sending = true; updateGate();
    try {
      const receipt = await request('/dispatch',{payload:editor.value,mode:snapshot.settings.mode,revision});
      result('Accepted by FOS · receipt ' + receipt.receiptId + ' · ' + receipt.state + '. Follow delivery below. Use New job template for a new message.');
      await refresh();
    } catch (error) { result(error.message); }
    finally { sending = false; updateGate(); }
  });
  async function poll() { await refresh(); window.setTimeout(poll,1000); }
  poll();
})();
