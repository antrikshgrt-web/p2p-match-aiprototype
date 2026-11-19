const API = '/api';
let invoices = [];
let selectedId = null;
let currentFilter = 'ALL';

// ---------------- Tabs ----------------
document.querySelectorAll('.tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('.tab').forEach(t => t.classList.remove('active'));
    document.querySelectorAll('.view').forEach(v => v.classList.remove('active'));
    tab.classList.add('active');
    document.getElementById('view-' + tab.dataset.view).classList.add('active');
    if (tab.dataset.view === 'audit') loadAudit();
  });
});

document.querySelectorAll('.filter').forEach(f => {
  f.addEventListener('click', () => {
    document.querySelectorAll('.filter').forEach(x => x.classList.remove('active'));
    f.classList.add('active');
    currentFilter = f.dataset.filter;
    renderList();
  });
});

// ---------------- Data loading ----------------
async function loadInvoices() {
  const res = await fetch(`${API}/invoices`);
  invoices = await res.json();
  renderList();
  updateQueueCount();
  if (selectedId) {
    const still = invoices.find(i => i.id === selectedId);
    if (still) renderDetail(still); else clearDetail();
  }
}

function updateQueueCount() {
  const pending = invoices.filter(i => i.status === 'EXCEPTION' || i.status === 'PENDING').length;
  document.getElementById('queueCount').textContent = `${pending} pending`;
}

function statusLabel(s) {
  return { CLEAN_MATCH: 'Clean match', EXCEPTION: 'Exception', RESOLVED: 'Resolved', PENDING: 'Pending' }[s] || s;
}

// ---------------- Queue list ----------------
function renderList() {
  const container = document.getElementById('invoiceList');
  container.innerHTML = '';
  const filtered = invoices.filter(i => currentFilter === 'ALL' || i.status === currentFilter)
                            .sort((a, b) => (a.status === 'EXCEPTION' ? -1 : 1) - (b.status === 'EXCEPTION' ? -1 : 1));

  if (filtered.length === 0) {
    container.innerHTML = '<div class="empty-state">No invoices in this view.</div>';
    return;
  }

  filtered.forEach(inv => {
    const row = document.createElement('div');
    row.className = `invoice-row status-${inv.status}` + (inv.id === selectedId ? ' selected' : '');
    row.innerHTML = `
      <div class="invoice-row-top">
        <span class="invoice-id">${inv.id}</span>
        <span class="status-badge status-${inv.status}">${statusLabel(inv.status)}</span>
      </div>
      <div class="invoice-vendor">${escapeHtml(inv.vendor)}</div>
      <div class="invoice-po">PO ${escapeHtml(inv.poReference)} · ${inv.currency} ${fmtMoney(inv.quantity * inv.unitPrice)}</div>
    `;
    row.addEventListener('click', () => {
      selectedId = inv.id;
      renderList();
      renderDetail(inv);
    });
    container.appendChild(row);
  });
}

function clearDetail() {
  document.getElementById('detailPane').innerHTML = '<div class="empty-state"><p>Select an invoice from the queue to review the AI\'s three-way match result.</p></div>';
}

// ---------------- Detail pane ----------------
function renderDetail(inv) {
  const pane = document.getElementById('detailPane');

  const po = inv.po;
  const grn = inv.grn;

  const verdictTitle = inv.status === 'CLEAN_MATCH' ? 'Clean match'
                      : inv.status === 'RESOLVED' ? `Resolved (${inv.humanDecision})`
                      : 'Exception — human review required';

  const issuesHtml = inv.issues && inv.issues.length
    ? `<ul class="issue-list">${inv.issues.map(i => `<li>${escapeHtml(i)}</li>`).join('')}</ul>`
    : `<p style="margin:0;">No discrepancies found between PO, GRN, and invoice within defined tolerances.</p>`;

  const decisionHtml = inv.humanDecision
    ? `<div class="decision-note"><b>Human decision recorded:</b> ${inv.humanDecision}${inv.decisionReason ? ' — ' + escapeHtml(inv.decisionReason) : ''}<br>
       <span style="color:var(--ink-soft)">at ${inv.decidedAt}</span></div>`
    : renderDecisionControls(inv);

  pane.innerHTML = `
    <div class="detail-head">
      <div>
        <h3>${escapeHtml(inv.vendor)}</h3>
        <div class="detail-sub">${inv.id} · PO ${escapeHtml(inv.poReference)} · submitted ${inv.submittedAt}</div>
      </div>
      <span class="status-badge status-${inv.status}" style="font-size:12.5px;padding:4px 10px;">${statusLabel(inv.status)}</span>
    </div>

    <div class="compare-grid">
      <div class="compare-col">
        <h4>Purchase Order (ME23N)</h4>
        ${po ? `
          <div class="compare-row"><span class="k">PO number</span><span class="v">${po.poNumber}</span></div>
          <div class="compare-row"><span class="k">Material</span><span class="v">${escapeHtml(po.material)}</span></div>
          <div class="compare-row"><span class="k">Quantity</span><span class="v">${po.quantity} ${po.uom}</span></div>
          <div class="compare-row"><span class="k">Unit price</span><span class="v">${fmtMoney(po.unitPrice)}</span></div>
          <div class="compare-row"><span class="k">GST no.</span><span class="v">${po.gstNumber}</span></div>
        ` : `<p style="color:var(--exception);font-size:13px;">No PO found for reference '${escapeHtml(inv.poReference)}'.</p>`}
      </div>

      <div class="compare-col">
        <h4>GRN (MIGO)</h4>
        ${grn ? `
          <div class="compare-row"><span class="k">GRN number</span><span class="v">${grn.grnNumber}</span></div>
          <div class="compare-row"><span class="k">Qty received</span><span class="v">${grn.quantityReceived}</span></div>
          <div class="compare-row"><span class="k">Date</span><span class="v">${grn.dateReceived}</span></div>
          <div class="compare-row"><span class="k">Partial?</span><span class="v">${grn.partial ? 'Yes' : 'No'}</span></div>
        ` : `<p style="color:var(--exception);font-size:13px;">No GRN posted against this PO yet.</p>`}
      </div>

      <div class="compare-col">
        <h4>Vendor Invoice (extracted)</h4>
        <div class="compare-row"><span class="k">Quantity</span><span class="v">${inv.quantity} ${inv.uom}</span></div>
        <div class="compare-row"><span class="k">Unit price</span><span class="v">${fmtMoney(inv.unitPrice)}</span></div>
        <div class="compare-row"><span class="k">GST no.</span><span class="v">${escapeHtml(inv.gstNumber)}</span></div>
        <div class="compare-row"><span class="k">Scan quality</span><span class="v">${inv.scanQuality}</span></div>
        ${inv.quantityVariancePercent !== null ? `<div class="compare-row"><span class="k">Qty variance</span><span class="v">${inv.quantityVariancePercent}%</span></div>` : ''}
        ${inv.priceVariancePercent !== null ? `<div class="compare-row"><span class="k">Price variance</span><span class="v">${inv.priceVariancePercent}%</span></div>` : ''}
      </div>
    </div>

    <div class="verdict-banner status-${inv.status}">
      <span class="verdict-title">${verdictTitle}</span>
      ${escapeHtml(inv.recommendation || '')}
    </div>

    <h4 style="margin-bottom:6px;">AI-detected issues</h4>
    ${issuesHtml}

    ${decisionHtml}
  `;

  if (!inv.humanDecision) attachDecisionHandlers(inv);
}

function renderDecisionControls(inv) {
  if (inv.status === 'CLEAN_MATCH') {
    return `
      <div class="decision-row">
        <button class="btn-confirm" data-action="CONFIRMED">Confirm &amp; proceed to parking</button>
      </div>
    `;
  }
  return `
    <div class="decision-row">
      <button class="btn-override" data-action="APPROVE_OVERRIDE">Approve override</button>
      <button class="btn-hold" data-action="HOLD">Hold</button>
      <button class="btn-escalate" data-action="ESCALATE">Escalate to procurement/stores</button>
    </div>
    <input type="text" class="reason-input" id="reasonInput" placeholder="Reason / note for this decision (optional)">
  `;
}

function attachDecisionHandlers(inv) {
  document.querySelectorAll('.decision-row button').forEach(btn => {
    btn.addEventListener('click', async () => {
      const reasonEl = document.getElementById('reasonInput');
      const reason = reasonEl ? reasonEl.value : '';
      btn.disabled = true;
      await fetch(`${API}/invoices/${inv.id}/decision`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ decision: btn.dataset.action, reason })
      });
      await loadInvoices();
    });
  });
}

// ---------------- Submit form ----------------
document.getElementById('invoiceForm').addEventListener('submit', async (e) => {
  e.preventDefault();
  const form = e.target;
  const payload = {
    vendor: form.vendor.value,
    poReference: form.poReference.value,
    quantity: parseFloat(form.quantity.value),
    uom: form.uom.value,
    unitPrice: parseFloat(form.unitPrice.value),
    gstNumber: form.gstNumber.value,
    currency: form.currency.value || 'INR',
    scanQuality: form.scanQuality.value
  };
  const res = await fetch(`${API}/invoices`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(payload)
  });
  const created = await res.json();

  let toast = document.querySelector('.result-toast');
  if (!toast) {
    toast = document.createElement('div');
    toast.className = 'result-toast';
    form.parentElement.appendChild(toast);
  }
  toast.innerHTML = `<b>${created.id}</b> classified as <b>${statusLabel(created.status)}</b>.<br>${escapeHtml(created.recommendation)}`;

  form.reset();
  await loadInvoices();
  selectedId = created.id;
  document.querySelector('.tab[data-view="queue"]').click();
  renderList();
  renderDetail(invoices.find(i => i.id === created.id));
});

// ---------------- Audit trail ----------------
async function loadAudit() {
  const res = await fetch(`${API}/audit`);
  const rows = await res.json();
  const body = document.getElementById('auditBody');
  body.innerHTML = rows.map(r => `
    <tr>
      <td class="mono">${r.timestamp}</td>
      <td class="mono">${r.invoiceId}</td>
      <td>${r.event}</td>
      <td>${statusLabel(r.aiStatus)}</td>
      <td>${r.humanDecision || '—'}</td>
      <td>${r.reason ? escapeHtml(r.reason) : (r.event === 'AI_CLASSIFIED' ? escapeHtml(r.aiRecommendation || '') : '—')}</td>
    </tr>
  `).join('');
}

// ---------------- Utilities ----------------
function fmtMoney(v) {
  return '₹' + Number(v).toLocaleString('en-IN', { maximumFractionDigits: 2 });
}
function escapeHtml(s) {
  if (s === null || s === undefined) return '';
  return String(s).replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
}

// ---------------- Init ----------------
loadInvoices();
setInterval(loadInvoices, 15000); // light polling so multiple reviewers stay in sync
