'use strict';

/* ==========================================================
   MONETRA FRONTEND
   Plain JavaScript. All money rules run on the server; this
   file draws screens and talks to /api/*.
   ========================================================== */

/* ==============================
   SETTINGS AND STATE
================================ */

const INR_PER_USD_FALLBACK = 88;     /* Used only if the server config cannot be loaded. */
const MAX_PAYMENT_INR = 100000;      /* Must match the server limit. */
const REMEMBER_KEY = 'monetra_remembered_email';
const POLL_MS = 20000;

const STATUS_LABELS = {
    CREATED: 'Created',
    AWAITING_APPROVAL: 'Awaiting approval',
    COMPLETED: 'Completed',
    CANCELLED: 'Cancelled',
    FAILED: 'Failed'
};

const PERIODS = {
    daily:   { title: 'Daily Spending Limit',   help: 'Set the maximum amount you want to spend in a day.',   spent: 'Spent today' },
    weekly:  { title: 'Weekly Spending Limit',  help: 'Set the maximum amount you want to spend in 7 days.',  spent: 'Spent in 7 days' },
    monthly: { title: 'Monthly Spending Limit', help: 'Set the maximum amount you want to spend this month.', spent: 'Spent this month' },
    yearly:  { title: 'Yearly Spending Limit',  help: 'Set the maximum amount you want to spend this year.',  spent: 'Spent this year' }
};

const NOTIFICATION_ICONS = {
    PAYMENT_RECEIVED: '↙',
    PAYMENT_SENT: '↗',
    PAYMENT_FAILED: '!',
    PAYOUT: '₹',
    REMINDER: '⏰',
};

const PARENT_VIEW = {
    forecastView: 'homeView',
    fraudView: 'homeView',
    notifView: 'homeView',
    transactionView: 'homeView',
    guardianView: 'none'
};

let me = { email: '', name: '', phone: '', paypalEmail: '' };
let payConfig = { paypalConfigured: false, webhookConfigured: false };
let payContext = null;          /* a household expense this payment will settle */
let guardianTab = 'ai';
let homeData = null;
let homeMonth = '';
let homeReport = null;
let chatHistory = [];
let contactTimer = null;
let pendingEmail = '';
let rateInfo = { inrPerUsd: INR_PER_USD_FALLBACK, currency: 'USD' };

let records = freshRecords();
let payments = [];

let currentView = 'homeView';
let payMethod = 'PHONE';
let payee = null;
let scannedName = '';
let draft = null;
let reviewRun = 0;
let lookupRun = 0;
let lookupTimer = null;
let period = 'daily';
let historyFilter = 'all';
let forecast = null;

let pollTimer = null;
let maxSeenNotification = -1;
let scanStream = null;
let scanTimer = null;

function freshRecords() {
    return {
        income: [],
        expenses: [],
        payments: [],
        loans: [],
        savings: [],
        goals: [],
        limits: { daily: 0, weekly: 0, monthly: 0, yearly: 0 },
        dailyLimit: 0
    };
}

/* ==============================
   SMALL HELPERS
================================ */

const $ = id => document.getElementById(id);

function money(value) {
    return new Intl.NumberFormat('en-IN', {
        style: 'currency',
        currency: 'INR',
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
    }).format(Number(value) || 0);
}

function usd(value) {
    return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' })
        .format(Number(value) || 0);
}

function dateKey(d) {
    return d.getFullYear() + '-' +
        String(d.getMonth() + 1).padStart(2, '0') + '-' +
        String(d.getDate()).padStart(2, '0');
}

function today() {
    return dateKey(new Date());
}

function displayDate(value) {
    if (!value) {
        return 'No date';
    }

    const d = new Date(value + 'T00:00:00');

    return Number.isNaN(d.getTime())
        ? value
        : d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' });
}

function displayDateTime(value) {
    const d = new Date(value);

    if (Number.isNaN(d.getTime())) {
        return '';
    }

    return d.toLocaleDateString('en-IN', { day: '2-digit', month: 'short', year: 'numeric' }) +
        ' • ' + d.toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit', hour12: false });
}

function safeArray(value) {
    return Array.isArray(value) ? value : [];
}

function makeElement(tag, className, text) {
    const el = document.createElement(tag);

    if (className) {
        el.className = className;
    }

    if (text !== undefined) {
        el.textContent = text;
    }

    return el;
}

function icon(id) {
    const ns = 'http://www.w3.org/2000/svg';
    const svg = document.createElementNS(ns, 'svg');
    const use = document.createElementNS(ns, 'use');

    svg.setAttribute('class', 'i');
    use.setAttribute('href', '#' + id);
    svg.appendChild(use);

    return svg;
}

function renderRecords(containerId, items, emptyText, renderItem) {
    const container = $(containerId);

    container.replaceChildren();

    if (!items.length) {
        container.appendChild(makeElement('div', 'empty-state', emptyText));

        return;
    }

    items.forEach(item => container.appendChild(renderItem(item)));
}

let toastTimer = null;

function showToast(text, ms) {
    const box = $('toast');

    box.textContent = text;
    box.classList.remove('hidden');

    clearTimeout(toastTimer);

    toastTimer = setTimeout(() => box.classList.add('hidden'), ms || 4500);
}

function validAmount(amount) {
    if (!Number.isFinite(amount) || amount <= 0 || amount > 1000000000) {
        showToast('Enter an amount greater than ₹0 and within the allowed limit.');

        return false;
    }

    return true;
}

/* ==============================
   SERVER REQUEST HELPERS
================================ */

async function apiJson(url, options, timeoutMs) {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), timeoutMs || 20000);

    try {
        const response = await fetch(url, { ...(options || {}), signal: controller.signal });

        const text = await response.text();

        let data = null;

        try {
            data = text ? JSON.parse(text) : null;
        } catch (error) {
            data = null;
        }

        if (!response.ok) {
            if (response.status === 401 && me.email) {
                sessionExpired();
            }

            const reason = (data && (data.error || data.message)) ||
                text.slice(0, 200) ||
                ('Request failed (' + response.status + ').');

            const err = new Error(reason);

            err.status = response.status;
            err.data = data;

            throw err;
        }

        return data;

    } catch (error) {
        if (error.name === 'AbortError') {
            throw new Error('The server took too long to respond.');
        }

        throw error;

    } finally {
        clearTimeout(timer);
    }
}

function postJson(url, body, timeoutMs) {
    return apiJson(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify(body)
    }, timeoutMs);
}

async function loadPaymentConfig() {
    try {
        const config = await apiJson('/api/payments/config', {}, 8000);

        if (config && Number(config.inrPerUsd) > 0) {
            rateInfo = { inrPerUsd: Number(config.inrPerUsd), currency: config.currency || 'USD' };
        }

        if (config) {
            payConfig = {
                paypalConfigured: !!config.paypalConfigured,
                webhookConfigured: !!config.webhookConfigured
            };
        }

    } catch (error) {
        /* Keep the fallback rate. The server decides the real amount. */
    }
}

/* ==============================
   RECORDS (saved on the server)
================================ */

function applyRecords(source) {
    records = { ...freshRecords(), ...source };

    records.income = safeArray(records.income);
    records.expenses = safeArray(records.expenses);
    records.payments = safeArray(records.payments);
    records.loans = safeArray(records.loans);
    records.savings = safeArray(records.savings);
    records.goals = safeArray(records.goals);

    const limits = records.limits && typeof records.limits === 'object' ? records.limits : {};

    records.limits = {
        daily: Number(limits.daily) || Number(records.dailyLimit) || 0,
        weekly: Number(limits.weekly) || 0,
        monthly: Number(limits.monthly) || 0,
        yearly: Number(limits.yearly) || 0
    };

    records.dailyLimit = records.limits.daily;
}

async function loadRecords() {
    let loaded = null;

    try {
        loaded = await apiJson('/api/data', {}, 15000);
    } catch (error) {
        loaded = null;
    }

    if (loaded && typeof loaded === 'object' && Object.keys(loaded).length) {
        applyRecords(loaded);
        return;
    }

    /* First run on the server: import this browser's old data once, if any. */

    let old = null;

    try {
        const saved = localStorage.getItem('monetra_records_' + me.email);

        old = saved ? JSON.parse(saved) : null;
    } catch (error) {
        old = null;
    }

    applyRecords(old || {});

    if (old) {
        saveRecords();
    }
}

let saveTimer = null;
let saveRun = 0;

/* Saves shortly after the last change; later saves win. */

function saveRecords() {
    clearTimeout(saveTimer);

    saveTimer = setTimeout(async function() {
        const run = ++saveRun;

        try {
            await apiJson('/api/data', {
                method: 'PUT',
                headers: { 'Content-Type': 'application/json' },
                body: JSON.stringify(records)
            }, 15000);

        } catch (error) {
            if (run === saveRun) {
                showToast('Could not save your data: ' + error.message);
            }
        }
    }, 250);
}

/* ==============================
   LOGIN AND REGISTRATION
================================ */

function showPage(pageId) {
    const register = pageId === 'registerPage';

    $('loginPage').classList.toggle('hidden', register);
    $('registerPage').classList.toggle('hidden', !register);

    $('tabSignIn').classList.toggle('active', !register);
    $('tabRegister').classList.toggle('active', register);

    $('loginMessage').textContent = '';
    $('registerMessage').textContent = '';

    if (register) {
        $('registerForm').classList.remove('hidden');
        $('otpForm').classList.add('hidden');
    }
}

function togglePassword(inputId, button) {
    const input = $(inputId);

    input.type = input.type === 'password' ? 'text' : 'password';

    button.textContent = input.type === 'password' ? 'Show' : 'Hide';
}

$('registerForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    const email = $('registerEmail').value.trim().toLowerCase();
    const phone = $('registerPhone').value.trim();
    const name = $('registerName').value.trim();
    const password = $('registerPassword').value;

    const message = $('registerMessage');
    const button = this.querySelector('button[type="submit"]');

    message.textContent = 'Sending OTP to your email...';
    button.disabled = true;

    try {
        const response = await fetch('/api/auth/send-otp', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password, phone, name })
        });

        const result = await response.text();

        if (!response.ok) {
            message.textContent = result;
            return;
        }

        pendingEmail = email;

        this.classList.add('hidden');

        $('otpForm').classList.remove('hidden');
        $('registerOtp').value = '';

        message.textContent = result;

    } catch (error) {
        message.textContent = 'Cannot connect to the server. Make sure Monetra is running.';

    } finally {
        button.disabled = false;
    }
});

$('otpForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    const otp = $('registerOtp').value.trim();
    const message = $('registerMessage');
    const button = this.querySelector('button[type="submit"]');

    message.textContent = 'Verifying OTP...';
    button.disabled = true;

    try {
        const response = await fetch('/api/auth/verify-otp', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: pendingEmail, otp })
        });

        const result = await response.text();

        message.textContent = result;

        if (response.ok) {
            const verifiedEmail = pendingEmail;

            $('registerForm').reset();
            this.reset();

            pendingEmail = '';

            $('registerForm').classList.remove('hidden');

            this.classList.add('hidden');

            setTimeout(() => {
                showPage('loginPage');

                $('loginEmail').value = verifiedEmail;

                $('loginMessage').textContent =
                    'Account created. You can now sign in.';
            }, 900);
        }

    } catch (error) {
        message.textContent = 'Cannot connect to the server. Please try again.';

    } finally {
        button.disabled = false;
    }
});

$('loginForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    const email = $('loginEmail').value.trim().toLowerCase();
    const password = $('loginPassword').value;

    const message = $('loginMessage');
    const button = this.querySelector('button[type="submit"]');

    message.textContent = 'Signing in...';
    button.disabled = true;

    try {
        const response = await fetch('/api/auth/login', {
            method: 'POST',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email, password })
        });

        const result = await response.text();

        if (response.ok) {
            rememberEmail(email);

            const profile = await apiJson('/api/auth/me', {}, 8000);

            await openDashboard(profile);

        } else {
            message.textContent = result;
        }

    } catch (error) {
        message.textContent = 'Cannot connect to the server. Make sure Monetra is running.';

    } finally {
        button.disabled = false;
    }
});

function rememberEmail(email) {
    try {
        if ($('rememberMe').checked) {
            localStorage.setItem(REMEMBER_KEY, email);
        } else {
            localStorage.removeItem(REMEMBER_KEY);
        }
    } catch (error) {
        /* Storage unavailable: skip remembering. */
    }
}

$('logoutButton').addEventListener('click', () => closeDashboard());

function sessionExpired() {
    closeDashboard('Your session ended. Please sign in again.');
}

async function openDashboard(profile) {
    me = {
        email: String(profile.email || '').toLowerCase(),
        name: profile.name || '',
        phone: profile.phone || '',
        paypalEmail: profile.paypalEmail || ''
    };

    $('dashboardUser').textContent = me.email;
    $('dashboardAvatar').textContent = (me.name || me.email).charAt(0).toUpperCase();

    $('authCard').classList.add('hidden');
    $('dashboardPage').classList.remove('hidden');

    document.body.classList.remove('auth-mode');

    await loadRecords();

    renderAll();

    navigateTo('homeView');

    await loadPayments();

    loadNotifications(false);
    loadForecast(false);
    loadGuardianBadge();

    clearInterval(pollTimer);

    pollTimer = setInterval(pollNotifications, POLL_MS);
}

function closeDashboard(note) {
    if (me.email) {
        fetch('/api/auth/logout', { method: 'POST' }).catch(function() {
            /* The session also expires on its own. */
        });
    }

    stopScan();

    clearInterval(pollTimer);

    $('dashboardPage').classList.add('hidden');
    $('authCard').classList.remove('hidden');

    document.body.classList.add('auth-mode');

    showPage('loginPage');

    $('loginForm').reset();
    $('loginEmail').value = me.email;

    if (note) {
        $('loginMessage').textContent = note;
    }

    me = { email: '', name: '', phone: '', paypalEmail: '' };
    pendingEmail = '';
    records = freshRecords();
    payments = [];
    forecast = null;
    homeData = null;
    homeReport = null;
    chatHistory = [];
    maxSeenNotification = -1;

    resetPayForm();

    $('agentLog').replaceChildren(makeElement('div', 'bubble bot',
        'Hi! Ask me something like “Can I afford ₹2,000 on shoes?” or “Prepare a payment of ₹800 to Ravi”.'));

    $('paymentResult').classList.add('hidden');
    $('bellBadge').classList.add('hidden');
    $('guardianBadge').classList.add('hidden');

    $('loginEmail').focus();
}

/* ==============================
   NAVIGATION (bottom bar)
================================ */

function navigateTo(viewId, action) {
    const target = $(viewId);

    if (!target) {
        return;
    }

    if (currentView === 'paymentView' && viewId !== 'paymentView') {
        stopScan();
    }

    currentView = viewId;

    document.querySelectorAll('.view-section').forEach(section => {
        section.classList.toggle('active', section === target);
    });

    const navView = PARENT_VIEW[viewId] || viewId;

    document.querySelectorAll('#bottomNav button').forEach(button => {
        const active = button.dataset.view === navView;

        button.classList.toggle('active', active);

        if (active) {
            button.setAttribute('aria-current', 'page');
        } else {
            button.removeAttribute('aria-current');
        }
    });

    $('dashboardMain').scrollTop = 0;

    if (viewId === 'paymentView') {
        showPayTab(action === 'receive' ? 'receive' : 'send');
    }

    if (viewId === 'transactionView') {
        loadPayments();
    }

    if (viewId === 'notifView') {
        loadNotifications(true);
    }

    if (viewId === 'forecastView') {
        loadForecast(true);
    }

    if (viewId === 'fraudView') {
        renderFraud();
    }

    if (viewId === 'spendingView') {
        renderSpending();
    }

    if (viewId === 'guardianView') {
        loadGuardian();
    }
}

document.querySelectorAll('#bottomNav button').forEach(button => {
    button.addEventListener('click', () => navigateTo(button.dataset.view));
});

/* ==============================
   RECORDS HELPERS
================================ */

function spentBetween(fromKey, toKey) {
    return records.expenses
        .filter(item => item.date >= fromKey && item.date <= toKey)
        .reduce((sum, item) => sum + Number(item.amount || 0), 0);
}

function spentInPeriod(which) {
    const now = new Date();
    const t = dateKey(now);

    if (which === 'daily') {
        return spentBetween(t, t);
    }

    if (which === 'weekly') {
        const start = new Date(now);

        start.setDate(start.getDate() - 6);

        return spentBetween(dateKey(start), t);
    }

    if (which === 'monthly') {
        return spentBetween(t.slice(0, 7) + '-01', t.slice(0, 7) + '-31');
    }

    return spentBetween(t.slice(0, 4) + '-01-01', t.slice(0, 4) + '-12-31');
}

function nextEmi() {
    return records.loans
        .filter(item => item.due >= today())
        .sort((a, b) => a.due.localeCompare(b.due))[0] || null;
}

function deleteRecord(listName, id) {
    records[listName] = records[listName].filter(item => item.id !== id);

    saveRecords();

    renderAll();
}

function recordCard(title, subtitle, amountText, buttonText, onDelete, chipText) {
    const card = makeElement('div', 'record-item');

    const left = makeElement('div', 'row');

    if (chipText) {
        left.appendChild(makeElement('span', 'icon-chip', chipText));
    }

    const info = makeElement('div');

    info.appendChild(makeElement('strong', '', title));

    if (subtitle) {
        info.appendChild(makeElement('div', 'sub', subtitle));
    }

    if (amountText) {
        info.appendChild(makeElement('div', 'sub', amountText));
    }

    left.appendChild(info);

    card.appendChild(left);

    if (buttonText) {
        const button = makeElement('button', 'small-button', buttonText);

        button.type = 'button';

        button.addEventListener('click', onDelete);

        card.appendChild(button);
    }

    return card;
}

/* ==============================
   PAY: TABS AND RECIPIENT
================================ */

function showPayTab(tab) {
    const send = tab !== 'receive';

    $('tabSend').classList.toggle('active', send);
    $('tabReceive').classList.toggle('active', !send);

    $('sendPanel').classList.toggle('hidden', !send);
    $('receivePanel').classList.toggle('hidden', send);

    if (!send) {
        stopScan();
        loadMyQr();
        renderReceived();
    }
}

async function loadMyQr() {
    $('myPhone').textContent = me.phone ? '+91 ' + me.phone : '';
    $('myName').textContent = me.name || me.email;

    try {
        const response = await fetch('/api/qr/me', { cache: 'no-store' });

        if (!response.ok) {
            throw new Error('QR not available');
        }

        const svg = await response.text();

        $('myQr').src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(svg);

    } catch (error) {
        $('myQr').removeAttribute('src');
        $('myName').textContent = 'Could not draw your QR code right now.';
    }
}

function renderReceived() {
    renderRecords(
        'receivedList',
        payments.filter(p => p.direction === 'RECEIVED' && p.status === 'COMPLETED').slice(0, 10),
        'No money received yet. Share your QR code or mobile number.',
        p => activityRow({
            kind: 'RECEIVED',
            title: 'From ' + p.counterparty,
            sub: displayDateTime(p.createdAt) + (p.description ? ' • ' + p.description : ''),
            amount: Number(p.amountInr)
        })
    );
}

function setMethod(method) {
    payMethod = method;

    $('methodPhone').classList.toggle('active', method === 'PHONE');
    $('methodQr').classList.toggle('active', method === 'QR');
    $('methodBank').classList.toggle('active', method === 'BANK');

    $('phoneBox').classList.toggle('hidden', method === 'BANK' || (method === 'QR' && !$('payPhone').value));
    $('qrBox').classList.toggle('hidden', method !== 'QR');
    $('bankBox').classList.toggle('hidden', method !== 'BANK');

    if (method !== 'QR') {
        stopScan();
    }
}

function resetPayForm() {
    const form = $('paymentForm');

    form.reset();

    payee = null;
    scannedName = '';
    draft = null;
    payContext = null;
    reviewRun++;

    $('recipientCard').classList.add('hidden');
    $('saveContact').classList.add('hidden');
    $('contactList').classList.add('hidden');
    $('bankDetected').textContent = '';
    $('nlMessage').textContent = '';
    $('paymentMessage').textContent = '';

    hideReview();
    stopScan();
    setMethod('PHONE');

    form.classList.remove('hidden');
}

function phoneKeyFrom(text) {
    const digits = String(text || '').replace(/\D/g, '');

    return digits.length < 10 ? '' : digits.slice(-10);
}

function renderRecipientCard(info, message, state) {
    const card = $('recipientCard');

    card.replaceChildren();
    card.classList.remove('hidden', 'found');

    if (state === 'found') {
        card.classList.add('found');
    }

    const chip = makeElement('span', 'icon-chip', info ? info.name.charAt(0).toUpperCase() : '?');

    card.appendChild(chip);

    const text = makeElement('div');

    text.appendChild(makeElement('strong', '', info ? info.name : message));

    if (info) {
        text.appendChild(makeElement('span', '', message));
    }

    card.appendChild(text);
}

async function lookupRecipient() {
    const key = phoneKeyFrom($('payPhone').value);

    payee = null;

    $('saveContact').classList.add('hidden');

    if (!key) {
        $('recipientCard').classList.add('hidden');
        return;
    }

    const run = ++lookupRun;

    renderRecipientCard(null, 'Looking up ' + key.slice(0, 2) + 'xxxxxx' + key.slice(8) + '...', 'wait');

    try {
        const info = await apiJson('/api/payments/recipient?phone=' + key, {}, 10000);

        if (run !== lookupRun) {
            return;
        }

        payee = { key, ...info };

        if (info.self) {
            renderRecipientCard(null, 'This is your own number. Enter someone else\'s.', 'warn');
            return;
        }

        refreshSaveContact(key);

        if (info.onMonetra) {
            renderRecipientCard(info, 'On Monetra ✓ • ' + info.maskedPhone, 'found');
            return;
        }

        const name = payMethod === 'QR' && scannedName ? scannedName : info.name;

        renderRecipientCard({ name },
            'Not on Monetra yet • ' + info.maskedPhone + ' • you can still pay through PayPal', 'new');

    } catch (error) {
        if (run === lookupRun) {
            renderRecipientCard(null, error.message, 'warn');
        }
    }
}

$('payPhone').addEventListener('input', function() {
    scannedName = payMethod === 'QR' ? scannedName : '';

    clearTimeout(lookupTimer);

    const key = phoneKeyFrom(this.value);

    if (key.length === 10 && String(this.value).replace(/\D/g, '').length <= 12) {
        lookupTimer = setTimeout(lookupRecipient, 350);
    } else {
        payee = null;
        $('recipientCard').classList.add('hidden');
        $('saveContact').classList.add('hidden');
    }
});

/* ==============================
   PAY: CONTACTS (search, save, phone contacts)
   Only people you saved, people you paid or were paid by,
   and your own phone's contact list if you add it.
================================ */

const CONTACT_TAGS = { SAVED: 'Saved', RECENT: 'Recent', PHONE: 'Phone' };

/* Android Chrome can open the phone's own contact list. Other browsers read a contacts file instead. */
const canPickContacts = !!(navigator.contacts && navigator.contacts.select);

function contactTools() {
    const row = makeElement('div', 'contact-tools');

    const add = makeElement('button', 'contact-item');

    add.type = 'button';

    add.appendChild(makeElement('span', 'icon-chip', '＋'));

    const text = makeElement('span');

    text.appendChild(document.createTextNode('Add contacts from your phone'));
    text.appendChild(makeElement('small', '', canPickContacts
        ? 'Pick them from your phone\'s contact list'
        : 'Choose the contacts file (.vcf) from your phone or Google Contacts'));

    add.appendChild(text);

    add.addEventListener('click', addPhoneContacts);

    const clear = makeElement('button', 'contact-clear', 'Remove');

    clear.type = 'button';
    clear.title = 'Remove the contacts that were added from your phone';

    clear.addEventListener('click', removePhoneContacts);

    row.appendChild(add);
    row.appendChild(clear);

    return row;
}

async function loadContacts(query) {
    const box = $('contactList');

    try {
        const list = safeArray(await apiJson('/api/contacts?q=' + encodeURIComponent(query || ''), {}, 8000));

        box.replaceChildren(contactTools());

        if (!list.length) {
            box.appendChild(makeElement('div', 'empty-state', query
                ? 'No contact matches. Type the mobile number below.'
                : 'No contacts yet. Add your phone contacts above, or pay someone and they appear here.'));
        }

        list.forEach(c => {
            const item = makeElement('button', 'contact-item');

            item.type = 'button';

            item.appendChild(makeElement('span', 'icon-chip', String(c.name).charAt(0).toUpperCase()));

            const text = makeElement('span');

            /* Your own contacts show the full number. People you only paid stay masked. */

            const number = c.saved ? c.phone.slice(0, 5) + ' ' + c.phone.slice(5) : c.maskedPhone;
            const monetra = c.onMonetra ? ' • On Monetra' + (c.monetraName ? ' as ' + c.monetraName : '') : '';

            text.appendChild(document.createTextNode(c.name));
            text.appendChild(makeElement('small', '', number + monetra));

            item.appendChild(text);
            item.appendChild(makeElement('span', 'tag', CONTACT_TAGS[c.source] || 'Recent'));

            item.addEventListener('click', () => pickContact(c));

            box.appendChild(item);
        });

        box.classList.remove('hidden');

    } catch (error) {
        box.classList.add('hidden');
    }
}

/* Reads names and numbers from a contacts file (.vcf), the format phones and Google Contacts export. */

function decodeQuotedPrintable(text) {
    const bytes = [];

    for (let i = 0; i < text.length; i++) {
        const hex = text.substr(i + 1, 2);

        if (text[i] === '=' && /^[0-9A-F]{2}$/i.test(hex)) {
            bytes.push(parseInt(hex, 16));
            i += 2;
        } else {
            bytes.push(text.charCodeAt(i) & 0xFF);
        }
    }

    try {
        return new TextDecoder('utf-8').decode(new Uint8Array(bytes));
    } catch (error) {
        return text;
    }
}

function parseVcard(fileText) {
    const lines = [];

    /* A long line may be continued on the next one: join them first. */

    String(fileText).replace(/\r\n?/g, '\n').split('\n').forEach(line => {
        const last = lines.length - 1;

        if (last >= 0 && /^[ \t]/.test(line)) {
            lines[last] += line.slice(1);

        } else if (last >= 0 && /QUOTED-PRINTABLE/i.test(lines[last]) && lines[last].endsWith('=')) {
            lines[last] = lines[last].slice(0, -1) + line;

        } else {
            lines.push(line);
        }
    });

    const found = [];

    let name = '';
    let phones = [];

    lines.forEach(line => {
        const colon = line.indexOf(':');

        if (colon < 0) {
            return;
        }

        const head = line.slice(0, colon).replace(/^item\d+\./i, '').toUpperCase();
        const field = head.split(';')[0];

        let value = line.slice(colon + 1);

        if (head.includes('QUOTED-PRINTABLE')) {
            value = decodeQuotedPrintable(value);
        }

        value = value.replace(/\\n/gi, ' ').replace(/\\([,;\\])/g, '$1').trim();

        if (field === 'BEGIN') {
            name = '';
            phones = [];

        } else if (field === 'FN' && value) {
            name = value;

        } else if (field === 'N' && !name) {
            /* N is "Family;Given;...": show it as "Given Family". */
            name = value.split(';').slice(0, 2).reverse().join(' ').trim();

        } else if (field === 'TEL') {
            phones.push(value);

        } else if (field === 'END') {
            phones.forEach(phone => found.push({ name, phone }));
        }
    });

    return found;
}

/* Keeps contacts that have a name and a mobile number (not a landline), one per number, and saves them on the server. */

async function sendPhoneContacts(found) {
    const seen = new Set();

    const list = found.filter(c => {
        const key = phoneKeyFrom(c.phone);

        if (!/^[6-9]/.test(key) || !String(c.name || '').trim() || seen.has(key)) {
            return false;
        }

        seen.add(key);

        return true;
    });

    if (!list.length) {
        showToast('No contacts with a name and a 10-digit mobile number were found.');
        return;
    }

    let added = 0;
    let updated = 0;

    try {
        for (let i = 0; i < list.length; i += 1000) {
            const result = await postJson('/api/contacts/import', { contacts: list.slice(i, i + 1000) }, 30000);

            added += Number(result.added) || 0;
            updated += Number(result.updated) || 0;
        }

    } catch (error) {
        showToast(error.message);
        return;
    }

    showToast(added || updated
        ? 'Added ' + added + ' contact' + (added === 1 ? '' : 's') + ' from your phone' +
            (updated ? ' and updated ' + updated : '') + '. Search by name or number.'
        : 'Those contacts are already in Monetra.');

    loadContacts($('contactSearch').value);
}

async function addPhoneContacts() {
    if (!canPickContacts) {
        $('contactsFile').click();
        return;
    }

    try {
        const picked = await navigator.contacts.select(['name', 'tel'], { multiple: true });

        const found = [];

        safeArray(picked).forEach(person => {
            const name = safeArray(person.name)[0] || '';

            safeArray(person.tel).forEach(phone => found.push({ name, phone }));
        });

        if (found.length) {
            await sendPhoneContacts(found);
        }

    } catch (error) {
        showToast('Your phone contacts could not be opened. Choose a contacts file (.vcf) instead.');

        $('contactsFile').click();
    }
}

$('contactsFile').addEventListener('change', async function() {
    const file = this.files && this.files[0];

    this.value = '';

    if (!file) {
        return;
    }

    if (file.size > 30 * 1024 * 1024) {
        showToast('That file is too large. Export your contacts as a .vcf file without photos.');
        return;
    }

    try {
        await sendPhoneContacts(parseVcard(await file.text()));

    } catch (error) {
        showToast('That file could not be read. Choose a contacts file that ends in .vcf.');
    }
});

async function removePhoneContacts() {
    if (!window.confirm('Remove all contacts that were added from your phone? Contacts you saved yourself stay.')) {
        return;
    }

    try {
        const result = await apiJson('/api/contacts/imported', { method: 'DELETE' }, 15000);

        showToast(result.removed
            ? 'Removed ' + result.removed + ' phone contact' + (result.removed === 1 ? '' : 's') + '.'
            : 'There were no phone contacts to remove.');

        loadContacts($('contactSearch').value);

    } catch (error) {
        showToast(error.message);
    }
}

function pickContact(contact) {
    $('contactList').classList.add('hidden');
    $('contactSearch').value = '';

    setMethod('PHONE');

    $('payPhone').value = contact.phone;

    lookupRecipient();

    $('payAmount').focus();
}

async function refreshSaveContact(key) {
    try {
        const list = safeArray(await apiJson('/api/contacts?q=' + key, {}, 8000));

        const saved = list.some(c => c.phone === key && c.saved);

        $('saveContact').classList.toggle('hidden', saved);

    } catch (error) {
        $('saveContact').classList.add('hidden');
    }
}

$('saveContact').addEventListener('click', async function() {
    if (!payee || !payee.key) {
        return;
    }

    let label = '';

    if (!payee.onMonetra) {
        label = (window.prompt('Name for this contact (so you can find them later):', scannedName || '') || '').trim();

        if (!label) {
            return;
        }
    }

    try {
        await postJson('/api/contacts', { phone: payee.key, name: label }, 10000);

        this.classList.add('hidden');

        showToast('Saved to your contacts.');

    } catch (error) {
        showToast(error.message);
    }
});

$('contactSearch').addEventListener('focus', function() {
    loadContacts(this.value);
});

$('contactSearch').addEventListener('input', function() {
    clearTimeout(contactTimer);

    const value = this.value;

    contactTimer = setTimeout(() => loadContacts(value), 250);
});

document.addEventListener('click', function(event) {
    if (!$('contactField').contains(event.target) && !$('contactList').contains(event.target)) {
        $('contactList').classList.add('hidden');
    }
});

/* ==============================
   PAY: SCAN A QR CODE
================================ */

function parseQr(text) {
    const t = String(text || '').trim();
    const lower = t.toLowerCase();

    let phone = '';
    let name = '';

    if (lower.startsWith('monetra://') || lower.startsWith('upi://')) {
        const query = t.includes('?') ? t.slice(t.indexOf('?') + 1) : '';

        new URLSearchParams(query).forEach((value, key) => {
            const k = key.toLowerCase();

            if (k === 'phone') {
                phone = phoneKeyFrom(value);
            } else if (k === 'pa' && !phone) {
                phone = phoneKeyFrom(value.split('@')[0]);
            } else if (k === 'pn') {
                name = value.slice(0, 60);
            }
        });

    } else {
        phone = phoneKeyFrom(t);
    }

    return { phone, name };
}

function applyScan(payeeInfo) {
    if (!payeeInfo.phone) {
        $('scanMessage').textContent =
            'That QR code has no mobile number in it. Try another code or enter the number.';
        return false;
    }

    scannedName = payeeInfo.name || '';

    setMethod('QR');

    $('payPhone').value = payeeInfo.phone;
    $('phoneBox').classList.remove('hidden');

    $('scanMessage').textContent = 'QR code read ✓ Check the name below, then enter the amount.';

    lookupRecipient();

    $('payAmount').focus();

    return true;
}

async function startScan() {
    if (!('BarcodeDetector' in window) || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        $('scanMessage').textContent =
            'Live scanning is not supported in this browser. Use "Choose QR image" to upload a photo or screenshot of the code.';
        return;
    }

    try {
        scanStream = await navigator.mediaDevices.getUserMedia({
            video: { facingMode: { ideal: 'environment' } },
            audio: false
        });

    } catch (error) {
        $('scanMessage').textContent =
            'Camera permission was not given. Allow the camera, or choose a QR image instead.';
        return;
    }

    const video = $('scanVideo');

    video.srcObject = scanStream;

    await video.play().catch(function() { /* Autoplay can be blocked; the stream still attaches. */ });

    $('scannerBox').classList.remove('hidden');
    $('scanStart').textContent = 'Stop camera';
    $('scanMessage').textContent = 'Point the camera at the QR code...';

    const detector = new BarcodeDetector({ formats: ['qr_code'] });

    scanTimer = setInterval(async function() {
        try {
            const found = await detector.detect(video);

            if (found.length) {
                const ok = applyScan(parseQr(found[0].rawValue));

                if (ok) {
                    stopScan();
                }
            }

        } catch (error) {
            /* Keep trying on the next frame. */
        }
    }, 350);
}

function stopScan() {
    clearInterval(scanTimer);

    scanTimer = null;

    if (scanStream) {
        scanStream.getTracks().forEach(track => track.stop());

        scanStream = null;
    }

    const box = $('scannerBox');

    if (box) {
        box.classList.add('hidden');
    }

    const button = $('scanStart');

    if (button) {
        button.textContent = 'Open camera';
    }
}

$('scanStart').addEventListener('click', function() {
    if (scanStream) {
        stopScan();
    } else {
        startScan();
    }
});

$('scanUpload').addEventListener('click', () => $('scanFile').click());

$('scanFile').addEventListener('change', async function() {
    const file = this.files && this.files[0];

    this.value = '';

    if (!file) {
        return;
    }

    $('scanMessage').textContent = 'Reading the QR code...';

    try {
        const response = await fetch('/api/qr/decode', {
            method: 'POST',
            headers: { 'Content-Type': 'application/octet-stream' },
            body: file
        });

        const data = await response.json();

        if (!response.ok) {
            throw new Error(data.error || 'Could not read that image.');
        }

        applyScan(parseQr(data.text));

    } catch (error) {
        $('scanMessage').textContent = error.message;
    }
});

/* ==============================
   PAY: BANK DETAILS
================================ */

$('bankIfsc').addEventListener('input', async function() {
    this.value = this.value.toUpperCase().replace(/[^A-Z0-9]/g, '');

    const out = $('bankDetected');

    if (this.value.length < 11) {
        out.textContent = '';
        return;
    }

    try {
        const info = await apiJson('/api/payments/bank?ifsc=' + encodeURIComponent(this.value), {}, 8000);

        out.textContent = '✓ ' + info.bankName;

    } catch (error) {
        out.textContent = error.message;
    }
});

/* ==============================
   PAY: DESCRIBE A PAYMENT WITH AI
================================ */

async function parsePaymentRequest() {
    const input = $('nlPrompt');
    const note = $('nlMessage');
    const button = $('nlParseButton');

    const text = input.value.trim();

    if (!text) {
        note.textContent = 'Type a request first, for example: Pay 9876543210 500 for lunch.';
        return;
    }

    note.textContent = 'Reading your request...';
    button.disabled = true;

    try {
        const result = await postJson('/api/guardian/parse', { text }, 15000);

        if (result.phone) {
            setMethod('PHONE');

            $('payPhone').value = result.phone;
            $('phoneBox').classList.remove('hidden');

            lookupRecipient();
        }

        if (Number(result.amountInr) > 0) {
            $('payAmount').value = Number(result.amountInr);
        }

        if (result.description) {
            $('payNote').value = result.description;
        }

        note.textContent = result.phone
            ? 'Details filled in. Check the detected name before you continue.'
            : 'I could not find a mobile number in that. Enter the number or scan a QR code.';

    } catch (error) {
        note.textContent = 'The AI could not read that request (' + error.message + '). You can fill in the form yourself.';

    } finally {
        button.disabled = false;
    }
}

$('nlParseButton').addEventListener('click', parsePaymentRequest);

$('nlPrompt').addEventListener('keydown', function(event) {
    if (event.key === 'Enter') {
        event.preventDefault();
        parsePaymentRequest();
    }
});

/* ==============================
   PAY: REVIEW BEFORE PAYPAL
================================ */

$('paymentForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const message = $('paymentMessage');

    message.textContent = '';

    const amount = Math.round(Number($('payAmount').value) * 100) / 100;

    if (!Number.isFinite(amount) || amount <= 0 || amount > MAX_PAYMENT_INR) {
        message.textContent = 'Enter an amount between ₹0.01 and ' + money(MAX_PAYMENT_INR) + '.';
        return;
    }

    const base = {
        method: payMethod,
        description: $('payNote').value.trim(),
        amountInr: amount
    };

    if (payMethod === 'BANK') {
        const ifsc = $('bankIfsc').value.trim().toUpperCase();
        const account = $('bankAccount').value.replace(/\s/g, '');

        if (!/^[A-Z]{4}0[A-Z0-9]{6}$/.test(ifsc)) {
            message.textContent = 'Enter a valid IFSC code, for example HDFC0001234.';
            return;
        }

        if (!/^[0-9]{9,18}$/.test(account)) {
            message.textContent = 'Enter a valid account number (9 to 18 digits).';
            return;
        }

        draft = { ...base, ifsc, accountNumber: account, recipientName: $('bankName').value.trim() };

    } else {
        const key = phoneKeyFrom($('payPhone').value);

        if (!key) {
            message.textContent = 'Enter a valid 10-digit mobile number or scan a QR code.';
            return;
        }

        if (payee && payee.self) {
            message.textContent = 'You cannot pay yourself.';
            return;
        }

        draft = { ...base, recipientPhone: key, scannedName: payMethod === 'QR' ? scannedName : '' };
    }

    if (payContext) {
        draft.homeExpenseId = payContext.homeExpenseId;
        draft.homeDueDate = payContext.homeDueDate;
    }

    startReview();
});

function statRow(label, value, className) {
    const row = makeElement('div', 'record-item');

    row.appendChild(makeElement('div', '', label));

    row.appendChild(makeElement('div', 'transaction-amount ' + (className || ''), value));

    return row;
}

function hideReview() {
    $('reviewPanel').classList.add('hidden');
    $('reviewMessage').textContent = '';
    $('reviewFraud').replaceChildren();

    $('continuePaypal').disabled = false;
}

const PERIOD_NAMES = { daily: 'daily', weekly: 'weekly (7-day)', monthly: 'monthly', yearly: 'yearly' };

function budgetLines(check) {
    const lines = [];

    safeArray(check.periods).forEach(p => {
        const name = PERIOD_NAMES[p.period] || p.period;
        const after = Number(p.remainingAfter);

        if (p.level === 'over') {
            lines.push({ bad: true, text: 'This payment puts you ' + money(Math.abs(after)) +
                ' over your ' + name + ' limit of ' + money(p.limit) + '.' });

        } else if (p.level === 'low') {
            lines.push({ warn: true, text: 'It fits your ' + name + ' limit but leaves only ' +
                money(after) + ' of ' + money(p.limit) + '.' });

        } else {
            lines.push({ text: 'It fits your ' + name + ' limit and leaves ' + money(after) + '.' });
        }
    });

    return lines;
}

const PERIOD_RESET = { daily: 'tomorrow', monthly: 'next month', yearly: 'next year' };

/* Plain advice worked out from the budget figures. Shown when the AI explanation is not available. */
function guardianAdvice(check) {
    const tips = [];
    const periods = safeArray(check.periods);

    if (!periods.length) {
        return tips;
    }

    const over = periods.find(p => p.level === 'over');

    if (over) {
        const name = PERIOD_NAMES[over.period] || over.period;
        const limit = Number(over.limit);
        const room = limit - Number(over.spent);
        const times = Math.round(Number(check.amountInr) / limit);
        const reset = PERIOD_RESET[over.period];

        if (times >= 2) {
            tips.push('This one payment is about ' + times + ' times your ' + name + ' limit.');
        }

        tips.push(room > 0
            ? 'You had ' + money(room) + ' left before this payment. Paying ' + money(room) +
                ' or less keeps you inside the limit.'
            : 'You had already used all of your ' + name + ' limit before this payment.');

        tips.push('If this payment is planned, raise the limit in Spending first.' +
            (reset ? ' If it can wait, your ' + over.period + ' limit starts again ' + reset + '.' : ''));
    }

    const set = periods.map(p => p.period);
    const missing = ['weekly', 'monthly'].filter(p => !set.includes(p));

    if (missing.length) {
        tips.push('No ' + missing.join(' or ') + ' limit is set, so this was checked only against your ' +
            set.join(' and ') + (set.length > 1 ? ' limits' : ' limit') +
            '. Add more limits in Spending for a fuller check.');
    }

    return tips;
}

async function startReview() {
    const message = $('paymentMessage');

    message.textContent = 'Checking your budget...';

    const run = ++reviewRun;

    let check;
    let risk;

    try {
        [check, risk] = await Promise.all([
            postJson('/api/guardian/check', { amountInr: draft.amountInr }, 15000),
            postJson('/api/payments/risk', draft, 15000)
        ]);

    } catch (error) {
        message.textContent = 'Could not check this payment: ' + error.message;
        return;
    }

    if (run !== reviewRun) {
        return;
    }

    message.textContent = '';

    draft.resolvedName = risk.recipientName;

    const lines = budgetLines(check);
    const bad = lines.some(l => l.bad);
    const warn = bad || lines.some(l => l.warn) || check.emiAtRisk || risk.flagged;

    $('paymentForm').classList.add('hidden');
    $('reviewPanel').classList.remove('hidden');

    const continueButton = $('continuePaypal');

    continueButton.disabled = risk.flagged;
    continueButton.textContent = warn ? 'Skip & Continue' : 'Continue to PayPal';

    $('reviewMessage').textContent = '';

    const summary = $('reviewSummary');

    summary.replaceChildren();

    const toText = draft.method === 'BANK'
        ? risk.recipientName + ' • ' + draft.ifsc
        : risk.recipientName;

    const daily = safeArray(check.periods).find(p => p.period === 'daily');

    summary.appendChild(statBox('To', toText));
    summary.appendChild(statBox('Amount', money(draft.amountInr)));

    summary.appendChild(statBox(
        'PayPal charge (approx.)',
        usd(draft.amountInr / rateInfo.inrPerUsd) + ' @ ₹' + rateInfo.inrPerUsd + '/$'
    ));

    summary.appendChild(statBox(
        'Left today after this',
        daily ? money(daily.remainingAfter) : 'No daily limit set',
        daily ? (Number(daily.remainingAfter) < 0 ? 'var(--red)' : 'var(--green)') : ''
    ));

    /* Rule-based advice is always shown first. */

    const rules = $('reviewRules');

    rules.replaceChildren();

    if (!lines.length) {
        rules.appendChild(makeElement('div', 'alert-box',
            'ℹ️ No spending limit is set, so Monetra cannot check this payment against a budget. ' +
            'Set one in Spending.'));
    }

    lines.forEach(l => rules.appendChild(makeElement(
        'div',
        'alert-box ' + (l.bad ? 'bad-box' : (l.warn ? 'warn-box' : 'good-box')),
        (l.bad || l.warn ? '⚠️ ' : '✅ ') + l.text
    )));

    if (check.emiDueSoon) {
        rules.appendChild(makeElement(
            'div',
            'alert-box ' + (check.emiAtRisk ? 'warn-box' : 'good-box'),
            (check.emiAtRisk ? '⚠️ ' : '✅ ') + check.nextEmiName + ' of ' +
            money(check.nextEmiAmount) + ' is due on ' + displayDate(check.nextEmiDue) +
            (check.emiAtRisk
                ? '. After this payment, your budget this month may not cover it.'
                : '. This payment still leaves room for it this month.')
        ));
    }

    showFraudBox(risk);

    requestAiAdvice(check, run);
}

function showFraudBox(risk) {
    const box = $('reviewFraud');

    box.replaceChildren();

    if (!risk.flagged) {
        return;
    }

    const warn = makeElement('div', 'alert-box warn-box');

    warn.appendChild(makeElement('strong', '', '🛡️ Fraud Guard: please check this payment'));

    const list = makeElement('ul');

    list.style.margin = '8px 0 0';
    list.style.paddingLeft = '20px';

    safeArray(risk.flags).forEach(text => list.appendChild(makeElement('li', '', text)));

    warn.appendChild(list);

    const label = makeElement('label', 'check-row');

    const box1 = document.createElement('input');

    box1.type = 'checkbox';
    box1.id = 'riskConfirm';

    box1.addEventListener('change', () => {
        $('continuePaypal').disabled = !box1.checked;
    });

    label.appendChild(box1);
    label.appendChild(document.createTextNode(
        'I know this recipient and want to continue with this payment.'));

    warn.appendChild(label);

    box.appendChild(warn);
}

async function requestAiAdvice(check, run) {
    const box = $('reviewAi');

    box.replaceChildren();

    /* Monetra's own rules advise at once. The AI notice replaces them when it arrives. */

    const tips = guardianAdvice(check);

    if (tips.length) {
        const advice = makeElement('div', 'alert-box' + (check.level === 'over' ? ' warn-box' : ''));

        advice.appendChild(makeElement('strong', '', '🛡️ Guardian advice'));

        const list = makeElement('ul');

        tips.forEach(text => list.appendChild(makeElement('li', '', text)));

        advice.appendChild(list);

        box.appendChild(advice);
    }

    const waiting = makeElement('div', 'footer-note',
        '🛡️ The Guardian AI is checking your limits, loans, EMIs and goals...');

    box.appendChild(waiting);

    try {
        /* The server works out the figures from the saved records. A model on this computer can be slow to start. */

        const result = await postJson('/api/guardian/explain', {
            amountInr: draft.amountInr,
            recipientName: draft.resolvedName,
            description: draft.description
        }, 45000);

        if (run !== reviewRun) {
            return;
        }

        box.replaceChildren(makeElement('div', 'alert-box' + (check.level === 'over' ? ' warn-box' : ''),
            '🛡️ Guardian AI: ' + String(result.advice || '').slice(0, 700)));

    } catch (error) {
        if (run !== reviewRun) {
            return;
        }

        /* No AI: the rule-based advice stays. */

        if (tips.length) {
            waiting.textContent = 'This advice comes from Monetra\'s budget rules.';
        } else {
            waiting.remove();
        }
    }
}

$('editPayment').addEventListener('click', function() {
    reviewRun++;

    hideReview();

    $('paymentForm').classList.remove('hidden');
});

$('continuePaypal').addEventListener('click', async function() {
    if (!draft) {
        return;
    }

    const button = this;
    const message = $('reviewMessage');
    const confirm = $('riskConfirm');

    button.disabled = true;

    message.textContent = 'Creating your PayPal Sandbox order...';

    try {
        const order = await postJson('/api/payments/create-order', {
            ...draft,
            confirmRisk: !!(confirm && confirm.checked)
        }, 30000);

        const url = new URL(order.approvalUrl);

        if (url.protocol !== 'https:' || !/(^|\.)paypal\.com$/.test(url.hostname)) {
            throw new Error('The server returned an unexpected payment address.');
        }

        message.textContent = 'Opening PayPal...';

        window.location.assign(url.toString());

    } catch (error) {
        if (error.status === 409 && error.data && error.data.needsConfirmation) {
            showFraudBox({ flagged: true, flags: error.data.flags });
            message.textContent = 'Please confirm the Fraud Guard notice above to continue.';

        } else {
            message.textContent = 'Could not start the payment: ' + error.message;
            button.disabled = false;
        }
    }
});

/* Coming back with the browser Back button leaves the page frozen: reset it. */

window.addEventListener('pageshow', function(event) {
    if (event.persisted) {
        $('continuePaypal').disabled = false;
        $('reviewMessage').textContent = '';
    }
});

/* ==============================
   PAYPAL RETURN (APPROVE / CANCEL)
================================ */

function showPaymentResult(text, kind) {
    const box = $('paymentResult');

    box.className = 'alert-box' + (kind ? ' ' + kind : '');

    box.textContent = text;
}

async function handlePaypalReturn(params) {
    const flow = params.get('paypal');
    const ref = params.get('ref') || '';
    const token = params.get('token') || '';

    navigateTo('paymentView');

    showPaymentResult('Checking your payment with PayPal...', '');

    try {
        if (flow === 'cancel') {
            await postJson('/api/payments/' + encodeURIComponent(ref) + '/cancel', {}, 15000);

            showPaymentResult('Payment cancelled. No money was taken.', 'bad-box');

        } else if (flow === 'return' && token) {
            const payment = await postJson('/api/payments/capture/' + encodeURIComponent(token), {}, 30000);

            if (payment.status === 'COMPLETED') {
                showPaymentResult(
                    '✅ Payment completed and confirmed: ' + money(payment.amountInr) +
                    ' (' + usd(payment.amountUsd) + ') to ' + payment.counterparty +
                    '. Reference ' + String(payment.paymentId).slice(0, 8) + '.',
                    'good-box'
                );

            } else {
                showPaymentResult(
                    'The payment was not completed (status: ' +
                    (STATUS_LABELS[payment.status] || payment.status) + '). ' + (payment.message || ''),
                    'bad-box'
                );
            }

        } else {
            showPaymentResult('This payment link is not valid.', 'bad-box');
        }

    } catch (error) {
        showPaymentResult(
            'Monetra could not confirm this payment: ' + error.message +
            ' Open Transactions and press Refresh to check it again.',
            'bad-box'
        );
    }

    await loadPayments();

    loadNotifications(false);
}

/* ==============================
   PAYMENTS FROM THE SERVER
================================ */

async function loadPayments() {
    if (!me.email) {
        return;
    }

    try {
        payments = safeArray(await apiJson('/api/payments', {}, 15000));

    } catch (error) {
        $('paypalStatus').replaceChildren(makeElement(
            'div', 'empty-state', 'Payment status is not available right now: ' + error.message));
        return;
    }

    syncPaymentsIntoRecords();

    renderAll();
}

/* Confirmed payments become expenses (sent) or income (received) exactly once. */

function syncPaymentsIntoRecords() {
    let changed = false;

    payments.forEach(p => {
        if (p.status !== 'COMPLETED') {
            return;
        }

        const ref = String(p.paymentId);
        const date = dateKey(new Date(p.createdAt));

        if (p.direction === 'SENT') {
            if (!records.expenses.some(item => item.ref === ref)) {
                records.expenses.unshift({
                    id: new Date(p.createdAt).getTime(),
                    name: 'Paid ' + p.counterparty + (p.description ? ' (' + p.description + ')' : ''),
                    amount: Number(p.amountInr),
                    date,
                    source: 'paypal',
                    ref
                });

                changed = true;
            }

        } else if (!records.income.some(item => item.ref === ref)) {
            records.income.unshift({
                id: new Date(p.createdAt).getTime(),
                name: 'Received from ' + p.counterparty + (p.description ? ' (' + p.description + ')' : ''),
                amount: Number(p.amountInr),
                date,
                source: 'payment',
                ref
            });

            changed = true;
        }
    });

    if (changed) {
        saveRecords();
    }
}

/* ==============================
   ACTIVITY / TRANSACTION HISTORY
================================ */

function activityItems() {
    const items = [];

    payments.forEach(p => {
        if (p.status !== 'COMPLETED') {
            return;
        }

        const sent = p.direction === 'SENT';

        let how = '';

        if (p.method === 'BANK') {
            how = 'Bank transfer' + (p.bankName ? ' • ' + p.bankName + ' ••••' + (p.bankLast4 || '') : '');
        } else if (p.method === 'QR') {
            how = 'QR payment';
        }

        items.push({
            kind: p.direction,
            title: (sent ? 'Paid ' : 'Received from ') + p.counterparty,
            sub: displayDateTime(p.createdAt) + (how ? ' • ' + how : '') +
                (p.description ? ' • ' + p.description : ''),
            amount: Number(p.amountInr),
            at: new Date(p.createdAt).getTime()
        });
    });

    records.expenses.filter(e => e.source !== 'paypal').forEach(e => {
        items.push({
            kind: 'EXPENSE',
            title: e.name,
            sub: displayDate(e.date) + ' • Recorded expense',
            amount: Number(e.amount),
            at: Number(e.id) || new Date(e.date + 'T12:00:00').getTime()
        });
    });

    items.sort((a, b) => b.at - a.at);

    return items;
}

function activityRow(item) {
    const row = makeElement('div', 'record-item');

    const left = makeElement('div', 'row');

    const inbound = item.kind === 'RECEIVED';

    left.appendChild(makeElement(
        'span',
        'icon-chip ' + (inbound ? 'in' : 'out'),
        inbound ? '↙' : (item.kind === 'SENT' ? '↗' : '−')
    ));

    const text = makeElement('div');

    text.appendChild(makeElement('strong', '', item.title));
    text.appendChild(makeElement('div', 'sub', item.sub));

    left.appendChild(text);

    row.appendChild(left);

    row.appendChild(makeElement(
        'div',
        'transaction-amount ' + (inbound ? 'income' : 'expense'),
        (inbound ? '+ ' : '− ') + money(item.amount)
    ));

    return row;
}

function renderHistory() {
    const all = activityItems();

    const shown = historyFilter === 'all' ? all : all.filter(i => i.kind === historyFilter);

    renderRecords('historyList', shown, 'No transactions yet.', activityRow);

    renderRecords('homeActivity', all.slice(0, 4),
        'Nothing yet. Send money or share your QR code to get started.', activityRow);

    $('historyNote').textContent =
        'Sent and received payments come from the Monetra server after PayPal confirms them. ' +
        'Recorded expenses are entered by you.';
}

document.querySelectorAll('#historyFilters .chip').forEach(chip => {
    chip.addEventListener('click', function() {
        historyFilter = this.dataset.filter;

        document.querySelectorAll('#historyFilters .chip').forEach(c => {
            c.classList.toggle('active', c === this);
        });

        renderHistory();
    });
});

function renderPaymentStatus() {
    const sent = payments.filter(p => p.direction === 'SENT').slice(0, 5);

    renderRecords('paypalStatus', sent, 'No PayPal payments yet. Send money to create one.', p => {
        const row = makeElement('div', 'record-item');

        const left = makeElement('div');

        left.appendChild(makeElement('strong', '', p.counterparty + ' • ' + money(p.amountInr)));

        left.appendChild(makeElement('div', 'sub',
            displayDateTime(p.createdAt) + ' • ' + usd(p.amountUsd) + ' • Ref ' + String(p.paymentId).slice(0, 8)));

        if (p.message) {
            left.appendChild(makeElement('div', 'sub' + (p.status === 'FAILED' ? ' warn' : ''), p.message));
        }

        if (p.status === 'AWAITING_APPROVAL' || (p.status === 'FAILED' && p.orderId)) {
            const retry = makeElement('button', 'small-button', 'Check with PayPal');

            retry.type = 'button';
            retry.style.marginTop = '8px';

            retry.addEventListener('click', () => recoverPayment(p.paymentId, retry));

            left.appendChild(retry);
        }

        row.appendChild(left);

        const status = String(p.status || '');

        row.appendChild(makeElement('span', 'badge ' + status.toLowerCase(), STATUS_LABELS[status] || status));

        return row;
    });
}

/* Asks the server to check PayPal again and fix our record (recovery). */

async function recoverPayment(paymentId, button) {
    button.disabled = true;
    button.textContent = 'Checking...';

    try {
        const result = await postJson('/api/payments/' + encodeURIComponent(paymentId) + '/refresh', {}, 30000);

        showToast(result.status === 'COMPLETED'
            ? 'PayPal confirmed this payment ✓'
            : (result.message || 'PayPal has not confirmed this payment yet.'));

    } catch (error) {
        showToast(error.message, 8000);
    }

    await loadPayments();

    loadNotifications(false);
}

$('refreshPaypalButton').addEventListener('click', loadPayments);

/* ==============================
   FRAUD GUARD ALERTS
================================ */

function renderFraud() {
    const flagged = payments.filter(p => p.direction === 'SENT' && p.riskFlags);

    renderRecords('fraudList', flagged, 'No payments have needed confirmation. You are all clear 🎉', p => {
        const row = makeElement('div', 'record-item');

        const left = makeElement('div');

        left.appendChild(makeElement('strong', '', p.counterparty + ' • ' + money(p.amountInr)));

        left.appendChild(makeElement('div', 'sub', displayDateTime(p.createdAt)));

        String(p.riskFlags).split(' | ').forEach(text => {
            left.appendChild(makeElement('div', 'sub warn', '⚠️ ' + text));
        });

        row.appendChild(left);

        const status = String(p.status || '');

        row.appendChild(makeElement('span', 'badge ' + status.toLowerCase(), STATUS_LABELS[status] || status));

        return row;
    });
}

/* ==============================
   NOTIFICATIONS
================================ */

function setBell(unread) {
    const badge = $('bellBadge');

    badge.textContent = unread > 9 ? '9+' : String(unread);

    badge.classList.toggle('hidden', !unread);
}

async function loadNotifications(markRead) {
    if (!me.email) {
        return;
    }

    try {
        const data = await apiJson('/api/notifications', {}, 10000);

        const items = safeArray(data.items);

        items.forEach(n => {
            maxSeenNotification = Math.max(maxSeenNotification, Number(n.id));
        });

        setBell(Number(data.unread) || 0);

        renderRecords('notifList', items, 'No notifications yet.', n => {
            const row = makeElement('div', 'record-item notif-item' + (n.seen ? '' : ' unread'));

            const left = makeElement('div', 'row');

            left.appendChild(makeElement(
                'span',
                'icon-chip ' + (n.type === 'PAYMENT_RECEIVED' ? 'in' : (n.type === 'PAYMENT_FAILED' ? 'out' : '')),
                NOTIFICATION_ICONS[n.type] || '↗'
            ));

            const text = makeElement('div');

            text.appendChild(makeElement('strong', '', n.message));
            text.appendChild(makeElement('div', 'sub', displayDateTime(n.createdAt)));

            left.appendChild(text);

            row.appendChild(left);

            return row;
        });

        if (markRead && Number(data.unread) > 0) {
            await postJson('/api/notifications/read', {}, 10000);

            setBell(0);
        }

    } catch (error) {
        /* Notifications are optional; try again on the next poll. */
    }
}

/* Every 20 seconds: show a pop-up for anything new and refresh money received. */

async function pollNotifications() {
    if (!me.email) {
        return;
    }

    try {
        const data = await apiJson('/api/notifications', {}, 10000);

        const fresh = safeArray(data.items)
            .filter(n => Number(n.id) > maxSeenNotification && !n.seen)
            .reverse();

        safeArray(data.items).forEach(n => {
            maxSeenNotification = Math.max(maxSeenNotification, Number(n.id));
        });

        setBell(Number(data.unread) || 0);

        if (fresh.length) {
            showToast('🔔 ' + fresh[fresh.length - 1].message, 7000);

            if (currentView === 'notifView') {
                loadNotifications(true);
            }

            await loadPayments();

            /* Household reminders change the Guardian screen. */
            if (fresh.some(n => n.type === 'REMINDER')) {
                if (currentView === 'guardianView') {
                    loadGuardian();
                } else {
                    loadGuardianBadge();
                }
            }
        }

    } catch (error) {
        /* Try again on the next poll. */
    }
}

/* ==============================
   SPENDING LIMITS
================================ */

document.querySelectorAll('#periodTabs button').forEach(button => {
    button.addEventListener('click', function() {
        period = this.dataset.period;

        renderSpending();
    });
});

function renderSpending() {
    document.querySelectorAll('#periodTabs button').forEach(button => {
        button.classList.toggle('active', button.dataset.period === period);
    });

    const text = PERIODS[period];
    const limit = Number(records.limits[period]) || 0;
    const spent = spentInPeriod(period);

    $('limitTitle').textContent = text.title;
    $('limitHelp').textContent = text.help;
    $('statSpentLabel').textContent = text.spent;

    const input = $('limitAmount');

    if (document.activeElement !== input) {
        input.value = limit || '';
    }

    $('statSpent').textContent = money(spent);
    $('statLimit').textContent = limit > 0 ? money(limit) : 'Not set';
    $('statLeft').textContent = limit > 0 ? money(Math.max(0, limit - spent)) : 'Not set';

    const status = $('statStatus');
    const alertBox = $('spendingAlert');

    alertBox.replaceChildren();

    if (!limit) {
        status.textContent = 'Not set';
        status.style.color = '';
        return;
    }

    if (spent > limit) {
        status.textContent = 'Over limit';
        status.style.color = 'var(--red)';

        alertBox.appendChild(makeElement('div', 'alert-box bad-box',
            '🚨 You are ' + money(spent - limit) + ' over your ' + period + ' limit of ' + money(limit) + '.'));

    } else {
        status.textContent = spent > limit * 0.8 ? 'Almost there' : 'On track';
        status.style.color = spent > limit * 0.8 ? 'var(--amber)' : 'var(--green)';

        alertBox.appendChild(makeElement('div', 'alert-box good-box',
            '✅ You are within your ' + period + ' limit.'));
    }
}

$('budgetForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const limit = Number($('limitAmount').value);

    if (!Number.isFinite(limit) || limit <= 0 || limit > 1000000000) {
        showToast('Enter a valid limit.');
        return;
    }

    records.limits[period] = limit;

    if (period === 'daily') {
        records.dailyLimit = limit;
    }

    saveRecords();

    renderAll();

    showToast('Your ' + period + ' limit is saved.');
});

$('expenseForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const name = $('expenseName').value.trim();
    const amount = Number($('expenseAmount').value);

    if (!name || !validAmount(amount)) {
        return;
    }

    records.expenses.unshift({ id: Date.now(), name, amount, date: today(), source: 'manual' });

    saveRecords();

    this.reset();

    renderAll();

    showToast('Expense added.');
});

/* ==============================
   SPENDING FORECAST
================================ */

async function loadForecast(render) {
    if (!me.email) {
        return;
    }

    try {
        forecast = await apiJson('/api/guardian/forecast', {}, 10000);

    } catch (error) {
        forecast = null;
    }

    updateTiles();

    if (render) {
        renderForecast();
    }
}

function forecastText(f) {
    if (!f) {
        return 'Forecast is not available right now.';
    }

    if (f.status === 'unknown') {
        return 'Set a monthly spending limit to compare your pace against a budget. ' +
            'So far this month you have spent ' + money(f.spentThisMonth) + '.';
    }

    if (f.status === 'over') {
        return 'At your current pace you will spend about ' + money(f.projectedMonthEnd) +
            ' this month, which is ' + money(Number(f.projectedMonthEnd) - Number(f.monthlyLimit)) +
            ' over your limit of ' + money(f.monthlyLimit) + '.';
    }

    if (f.status === 'watch') {
        return 'You are close: about ' + money(f.projectedMonthEnd) + ' projected against a limit of ' +
            money(f.monthlyLimit) + '. A little less spending will keep you safe.';
    }

    return 'You are on track: about ' + money(f.projectedMonthEnd) + ' projected against a limit of ' +
        money(f.monthlyLimit) + '.';
}

function renderForecast() {
    const card = $('forecastCard');

    card.replaceChildren();

    if (!forecast) {
        card.appendChild(makeElement('div', 'empty-state', 'Forecast is not available right now.'));
        return;
    }

    const f = forecast;
    const limit = Number(f.monthlyLimit) || 0;
    const projected = Number(f.projectedMonthEnd) || 0;

    const head = makeElement('div', 'card-head');

    head.appendChild(makeElement('h2', '', 'This month'));

    const labels = { on_track: 'On track', watch: 'Watch out', over: 'Over budget', unknown: 'No limit set' };

    head.appendChild(makeElement(
        'span',
        'badge ' + (f.status === 'over' ? 'failed' : (f.status === 'on_track' ? 'completed' : 'flag')),
        labels[f.status] || f.status
    ));

    card.appendChild(head);

    card.appendChild(makeElement('p', '', forecastText(f)));

    if (limit > 0) {
        const scale = Math.max(limit, projected, 1);

        const meter = makeElement('div', 'meter');

        const fill = makeElement('div', 'fill' + (projected > limit ? ' over' : ''));

        fill.style.width = Math.min(100, projected / scale * 100) + '%';

        meter.appendChild(fill);

        const tick = makeElement('div', 'tick');

        tick.style.left = (limit / scale * 100) + '%';

        meter.appendChild(tick);

        card.appendChild(meter);

        const legend = makeElement('div', 'meter-legend');

        legend.appendChild(makeElement('span', '', 'Projected ' + money(projected)));
        legend.appendChild(makeElement('span', '', 'Limit ' + money(limit)));

        card.appendChild(legend);
    }

    const grid = makeElement('div', 'stat-grid');

    [
        ['Spent so far', money(f.spentThisMonth)],
        ['Average per day', money(f.averagePerDay)],
        ['Day of month', f.dayOfMonth + ' of ' + f.daysInMonth],
        ['Limit runs out', f.limitRunsOutOnDay ? 'Around day ' + f.limitRunsOutOnDay : 'Not this month']
    ].forEach(pair => {
        const stat = makeElement('div', 'stat');

        stat.appendChild(makeElement('div', 'l', pair[0]));
        stat.appendChild(makeElement('div', 'v', pair[1]));

        grid.appendChild(stat);
    });

    card.appendChild(grid);

    if (limit <= 0) {
        const go = makeElement('button', 'btn-gold', 'Set a monthly limit');

        go.type = 'button';
        go.style.cssText = 'width:100%;margin-top:14px';

        go.addEventListener('click', () => {
            period = 'monthly';
            navigateTo('spendingView');
        });

        card.appendChild(go);
    }
}

$('forecastExplain').addEventListener('click', async function() {
    const box = $('forecastAi');
    const button = this;

    button.disabled = true;

    box.replaceChildren(makeElement('div', 'empty-state', '🛡️ The Guardian is thinking...'));

    try {
        const result = await postJson('/api/guardian/forecast/explain', {}, 20000);

        box.replaceChildren(makeElement('div', 'alert-box', '🛡️ Guardian AI: ' + String(result.advice || '').slice(0, 700)));

    } catch (error) {
        box.replaceChildren(
            makeElement('div', 'alert-box', '🛡️ ' + forecastText(forecast)),
            makeElement('div', 'footer-note', 'The AI explanation is not available right now, so this summary uses Monetra\'s rules.')
        );

    } finally {
        button.disabled = false;
    }
});

/* ==============================
   LOANS AND EMI
================================ */

$('loanForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const name = $('loanName').value.trim();
    const amount = Number($('loanAmount').value);
    const due = $('loanDue').value;
    const notes = $('loanNotes').value.trim();

    if (!name || !validAmount(amount) || !due) {
        return;
    }

    records.loans.unshift({ id: Date.now(), name, amount, due, notes });

    saveRecords();

    this.reset();

    renderAll();
});

/* An EMI is paid every month, so this is what it costs per day, per month and per year. */
function emiPlan(amount) {
    const perYear = (Number(amount) || 0) * 12;

    return { daily: perYear / 365, monthly: perYear / 12, yearly: perYear };
}

/* How much to save for all EMIs together, compared with the spending limit the user has set. */
function renderLoanPlan() {
    const box = $('loanPlan');

    box.replaceChildren();

    if (!records.loans.length) {
        return;
    }

    const plan = emiPlan(records.loans.reduce((sum, loan) => sum + Number(loan.amount || 0), 0));
    const next = nextEmi();

    box.appendChild(makeElement('div', 'hint', 'To cover your EMIs, you must save:'));

    const grid = makeElement('div', 'stat-grid');

    grid.append(
        statBox('Every day', money(plan.daily)),
        statBox('Every month', money(plan.monthly)),
        statBox('Every year', money(plan.yearly)),
        statBox('Next EMI due', next ? displayDate(next.due) : 'Overdue', next ? '' : 'var(--red)')
    );

    box.appendChild(grid);

    const period = ['monthly', 'daily', 'yearly'].find(p => Number(records.limits[p]) > 0);

    if (!period) {
        return;
    }

    const limit = Number(records.limits[period]);
    const need = plan[period];

    box.appendChild(need > limit
        ? makeElement('div', 'alert-box warn-box', '⚠️ Your EMIs need ' + money(need) +
            ' every ' + period.replace('ly', '').replace('dai', 'day') + ', which is more than your ' +
            period + ' spending limit of ' + money(limit) + '.')
        : makeElement('div', 'alert-box', 'Your EMIs take ' + money(need) + ' of your ' + money(limit) +
            ' ' + period + ' spending limit (' + Math.round(need / limit * 100) + '%).'));
}

function updateLoans() {
    $('loanCount').textContent = records.loans.length + ' ACTIVE';

    renderLoanPlan();

    renderRecords('loansList', records.loans, 'No loans or EMI reminders added yet.', loan => {
        const overdue = loan.due < today();
        const dueToday = loan.due === today();

        const label = overdue ? 'OVERDUE' : (dueToday ? 'DUE TODAY' : 'Due ' + displayDate(loan.due));

        const plan = emiPlan(loan.amount);

        const card = recordCard(
            loan.name,
            label + ' • EMI: ' + money(loan.amount) + (loan.notes ? ' • ' + loan.notes : ''),
            'Save ' + money(plan.daily) + ' a day • ' + money(plan.monthly) + ' a month • ' +
                money(plan.yearly) + ' a year',
            'Delete',
            () => deleteRecord('loans', loan.id),
            '🏦'
        );

        if (overdue || dueToday) {
            const note = makeElement('div', 'sub warn',
                overdue ? '⚠️ This EMI due date has passed.' : '⚠️ This EMI is due today.');

            card.querySelector('.row > div').appendChild(note);
        }

        return card;
    });
}

/* ==============================
   SAVINGS AND GOALS
================================ */

$('savingsForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const name = $('savingsName').value.trim();
    const amount = Number($('savingsAmount').value);
    const savingPeriod = $('savingsPeriod').value;

    if (!name || !validAmount(amount)) {
        return;
    }

    records.savings.unshift({ id: Date.now(), name, amount, period: savingPeriod, date: today() });

    saveRecords();

    this.reset();

    renderAll();
});

$('goalForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const name = $('goalName').value.trim();
    const target = Number($('goalTarget').value);
    const saved = Number($('goalSaved').value);

    if (!name || !validAmount(target) || !Number.isFinite(saved) || saved < 0 || saved > 1000000000) {
        showToast('Enter valid goal amounts.');
        return;
    }

    records.goals.unshift({ id: Date.now(), name, target, saved });

    saveRecords();

    this.reset();

    $('goalSaved').value = '0';

    renderAll();
});

function updateSavings() {
    const total = records.savings.reduce((sum, item) => sum + Number(item.amount || 0), 0);

    $('savingsTotal').textContent = money(total);

    renderRecords('savingsList', records.savings, 'No savings entries yet.', item => recordCard(
        item.name,
        item.period + ' savings • ' + displayDate(item.date),
        money(item.amount),
        'Delete',
        () => deleteRecord('savings', item.id),
        '💰'
    ));
}

function updateGoals() {
    renderRecords('goalsList', records.goals, 'No savings goals created yet. Once you add a goal, it will appear here to track your progress.', goal => {
        const pct = Math.min(100, goal.target > 0 ? (goal.saved / goal.target) * 100 : 0);

        const card = makeElement('div', 'record-item');

        const info = makeElement('div');

        info.style.width = '100%';

        info.appendChild(makeElement('strong', '', '🎯 ' + goal.name));

        info.appendChild(makeElement('div', 'sub',
            money(goal.saved) + ' saved of ' + money(goal.target) + ' • ' + Math.round(pct) + '%'));

        const progress = makeElement('div', 'progress');

        const bar = makeElement('div', 'progress-bar');

        bar.style.width = pct + '%';

        progress.appendChild(bar);

        info.appendChild(progress);

        info.appendChild(makeElement('div', 'sub',
            goal.saved >= goal.target
                ? '🎉 Goal reached!'
                : 'Still need ' + money(Math.max(0, goal.target - goal.saved))));

        card.appendChild(info);

        const del = makeElement('button', 'small-button', 'Delete');

        del.type = 'button';

        del.addEventListener('click', () => deleteRecord('goals', goal.id));

        card.appendChild(del);

        return card;
    });
}

/* ==============================
   HOME TILES AND REDRAW
================================ */

function updateTiles() {
    const emi = nextEmi();

    $('tileLoans').textContent = emi
        ? emi.name + ' • ' + money(emi.amount) + ' due ' + displayDate(emi.due)
        : 'Add an EMI to get reminders.';

    const goal = records.goals
        .filter(item => item.saved < item.target)
        .sort((a, b) => (b.saved / b.target) - (a.saved / a.target))[0];

    $('tileGoals').textContent = goal
        ? goal.name + ' is ' + Math.round(goal.saved / goal.target * 100) + '% complete'
        : (records.goals.length ? 'All your goals are reached 🎉' : 'Create a goal and track it.');

    $('tileForecast').textContent = forecast
        ? forecastText(forecast).split('. ')[0].replace(/\.$/, '') + '.'
        : 'See where this month is heading.';

    const flagged = payments.filter(p => p.direction === 'SENT' && p.riskFlags).length;

    $('tileFraud').textContent = flagged
        ? flagged + ' payment' + (flagged > 1 ? 's' : '') + ' needed your confirmation'
        : 'All clear. Every payment is checked first.';
}

function renderWeekBars() {
    const bars = $('weekBars');

    bars.replaceChildren();

    const days = [];

    for (let i = 6; i >= 0; i--) {
        const d = new Date();

        d.setDate(d.getDate() - i);

        const key = dateKey(d);

        days.push({
            total: spentBetween(key, key),
            label: d.toLocaleDateString('en-IN', { weekday: 'short' }),
            today: i === 0
        });
    }

    const max = Math.max(1, ...days.map(day => day.total));

    days.forEach(day => {
        const col = makeElement('div', 'bar-col');

        col.appendChild(makeElement('div', 'bar-val', day.total ? '₹' + Math.round(day.total) : ''));

        const bar = makeElement('div', 'bar' + (day.today ? ' today' : ''));

        bar.style.height = Math.max(3, Math.round(day.total / max * 120)) + 'px';

        bar.title = day.label + ': ' + money(day.total);

        col.appendChild(bar);

        col.appendChild(makeElement('div', 'bar-day', day.label));

        bars.appendChild(col);
    });
}

function renderAll() {
    updateLoans();
    updateSavings();
    updateGoals();
    renderHistory();
    renderPaymentStatus();
    renderSpending();
    renderWeekBars();
    renderFraud();
    updateTiles();

    if (currentView === 'paymentView' && !$('receivePanel').classList.contains('hidden')) {
        renderReceived();
    }

    if (currentView === 'guardianView') {
        renderPaypalSystem();
    }
}

/* ==============================
   MONETRA GUARDIAN
   Section 1: AI & Payments (AI Financial Guardian, PayPal Payment System)
   Section 2: Family & Home (Home Expense Guardian)
================================ */

function showGuardianTab(tab) {
    guardianTab = tab === 'family' ? 'family' : 'ai';

    $('gTabAi').classList.toggle('active', guardianTab === 'ai');
    $('gTabFamily').classList.toggle('active', guardianTab === 'family');

    $('gAi').classList.toggle('hidden', guardianTab !== 'ai');
    $('gFamily').classList.toggle('hidden', guardianTab !== 'family');
}

async function loadGuardian() {
    showGuardianTab(guardianTab);

    renderPaypalSystem();

    await Promise.all([loadHome(), loadPaymentConfig()]);

    renderPaypalSystem();

    updateGuardianBadge();
}

/* The badge on the Guardian button: things waiting for you. */

async function loadGuardianBadge() {
    if (!me.email) {
        return;
    }

    await loadHome();

    updateGuardianBadge();
}

function updateGuardianBadge() {
    let count = 0;

    if (homeData && homeData.summary) {
        count += Number(homeData.summary.pendingCount || 0) + Number(homeData.summary.overdueCount || 0);
    }

    const badge = $('guardianBadge');

    badge.textContent = count > 9 ? '9+' : String(count);

    badge.classList.toggle('hidden', !count);
}

/* ---------- AI Financial Guardian (chat) ---------- */

function addBubble(kind, text) {
    const bubble = makeElement('div', 'bubble ' + kind, text);

    $('agentLog').appendChild(bubble);

    $('agentLog').scrollTop = $('agentLog').scrollHeight;

    return bubble;
}

function addPreparedPayment(card) {
    const box = makeElement('div', 'prep-card');

    box.appendChild(makeElement('strong', '', 'Prepared payment'));

    box.appendChild(makeElement('div', '',
        money(card.amountInr) + ' to ' + card.recipientName +
        (card.onMonetra ? ' (on Monetra)' : ' (not on Monetra yet)') +
        (card.description ? ' • ' + card.description : '')));

    box.appendChild(makeElement('div', 'sub', 'Nothing has been sent. Review it first, then you confirm.'));

    const go = makeElement('button', 'btn-gold', 'Review & confirm');

    go.type = 'button';

    go.addEventListener('click', () => loadPreparedPayment(card));

    box.appendChild(go);

    $('agentLog').appendChild(box);
}

function addPreparedReminder(reminder) {
    const box = makeElement('div', 'prep-card');

    box.appendChild(makeElement('strong', '', 'Prepared reminder'));

    box.appendChild(makeElement('div', '',
        reminder.name + ' • ' + money(reminder.amount) + ' • ' + String(reminder.frequency).toLowerCase() +
        ' from ' + displayDate(reminder.startDate)));

    box.appendChild(makeElement('div', 'sub',
        'Not saved yet. A reminder only reminds you: it never moves money.'));

    const actions = makeElement('div', 'row-actions');

    const save = makeElement('button', 'btn-gold', 'Save reminder');

    save.type = 'button';

    save.addEventListener('click', async () => {
        save.disabled = true;

        try {
            await postJson('/api/home/expenses', {
                name: reminder.name,
                amount: reminder.amount,
                frequency: reminder.frequency,
                startDate: reminder.startDate,
                category: reminder.category,
                remindDaysBefore: reminder.remindDaysBefore
            }, 15000);

            box.replaceChildren(makeElement('div', '', '✅ Reminder saved: ' + reminder.name + '.'));

            loadHome();

        } catch (error) {
            save.disabled = false;
            showToast(error.message);
        }
    });

    const dismiss = makeElement('button', '', 'Dismiss');

    dismiss.type = 'button';

    dismiss.addEventListener('click', () => box.remove());

    actions.appendChild(save);
    actions.appendChild(dismiss);

    box.appendChild(actions);

    $('agentLog').appendChild(box);
}

/* Opens the Pay screen with the prepared details and goes straight to the review step. */

function loadPreparedPayment(card) {
    navigateTo('paymentView', 'send');

    resetPayForm();

    setMethod('PHONE');

    $('payPhone').value = card.recipientPhone;
    $('payAmount').value = Number(card.amountInr);
    $('payNote').value = card.description || '';

    $('phoneBox').classList.remove('hidden');

    lookupRecipient();

    $('paymentForm').requestSubmit();
}

$('agentForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    const input = $('agentInput');
    const text = input.value.trim();

    if (!text) {
        return;
    }

    input.value = '';

    addBubble('user', text);

    const wait = addBubble('bot wait', 'Checking your records...');

    $('agentSend').disabled = true;

    try {
        const result = await postJson('/api/agent/chat', {
            message: text,
            history: chatHistory.slice(-8)
        }, 60000);

        wait.remove();

        addBubble('bot', result.reply);

        safeArray(result.payments).forEach(addPreparedPayment);
        safeArray(result.reminders).forEach(addPreparedReminder);

        chatHistory.push({ role: 'user', text });
        chatHistory.push({ role: 'assistant', text: String(result.reply || '') });

    } catch (error) {
        wait.remove();

        addBubble('bot', error.status === 503
            ? 'The AI is not set up on this server yet. Whoever runs Monetra can switch it on for free ' +
              '(see "Free AI" in the README: Google Gemini, Groq or Ollama). Everything else still works.'
            : 'Sorry, I could not answer that: ' + error.message);

    } finally {
        $('agentSend').disabled = false;
        input.focus();
    }
});

document.querySelectorAll('#agentSuggestions .chip').forEach(chip => {
    chip.addEventListener('click', function() {
        $('agentInput').value = this.dataset.q;

        $('agentForm').requestSubmit();
    });
});

/* ---------- PayPal Payment System ---------- */

const PAYOUT_LABELS = {
    WAITING_EMAIL: 'Waiting for PayPal email',
    PENDING: 'Pending',
    SENT: 'Sent to PayPal',
    FAILED: 'Failed',
    NONE: '—'
};

function sysLine(label, ok, text) {
    const line = makeElement('div', 'sys-line');

    const left = makeElement('span');

    left.appendChild(makeElement('span', ok ? 'dot-ok' : 'dot-off'));
    left.appendChild(document.createTextNode(label));

    line.appendChild(left);
    line.appendChild(makeElement('span', 'muted', text));

    return line;
}

function renderPaypalSystem() {
    const box = $('paypalSystem');

    box.replaceChildren(
        sysLine('PayPal Sandbox', payConfig.paypalConfigured,
            payConfig.paypalConfigured ? 'Connected (test money only)' : 'Not configured on the server'),
        sysLine('Webhooks', payConfig.webhookConfigured,
            payConfig.webhookConfigured ? 'Verified events' : 'Not configured (PAYPAL_WEBHOOK_ID)'),
        sysLine('Your PayPal email', !!me.paypalEmail,
            me.paypalEmail || 'Not added: you cannot be paid out yet')
    );

    if (document.activeElement !== $('paypalEmailInput')) {
        $('paypalEmailInput').value = me.paypalEmail || '';
    }

    $('paypalEmailHint').textContent = me.paypalEmail
        ? 'Money people send you is paid out to ' + me.paypalEmail + ' by PayPal Payouts.'
        : 'Add your PayPal Sandbox email so the money people send you is paid out to your PayPal account.';

    const wrap = $('paymentTable');

    wrap.replaceChildren();

    const rows = payments.slice(0, 10);

    if (!rows.length) {
        wrap.appendChild(makeElement('div', 'empty-state', 'No payments yet. Send money or share your QR code.'));
        return;
    }

    const scroller = makeElement('div', 'table-wrap');
    const table = makeElement('table', 'table');

    const head = document.createElement('tr');

    ['Recipient', 'Amount', 'Date', 'Status', 'Payout'].forEach(h => head.appendChild(makeElement('th', '', h)));

    const thead = document.createElement('thead');

    thead.appendChild(head);
    table.appendChild(thead);

    const tbody = document.createElement('tbody');

    rows.forEach(p => {
        const tr = document.createElement('tr');

        const sent = p.direction === 'SENT';

        tr.appendChild(makeElement('td', '', (sent ? '↗ ' : '↙ ') + p.counterparty));

        tr.appendChild(makeElement('td', 'num ' + (sent ? 'expense' : 'income'),
            (sent ? '− ' : '+ ') + money(p.amountInr)));

        tr.appendChild(makeElement('td', '',
            new Date(p.createdAt).toLocaleDateString('en-IN', { day: '2-digit', month: 'short' })));

        const status = makeElement('td');

        status.appendChild(makeElement('span', 'badge ' + String(p.status).toLowerCase(),
            STATUS_LABELS[p.status] || p.status));

        tr.appendChild(status);

        tr.appendChild(makeElement('td', 'muted',
            p.status === 'COMPLETED' ? (PAYOUT_LABELS[p.payoutStatus] || '—') : '—'));

        tbody.appendChild(tr);
    });

    table.appendChild(tbody);
    scroller.appendChild(table);
    wrap.appendChild(scroller);
}

$('gRefreshPayments').addEventListener('click', async function() {
    await loadPayments();

    renderPaypalSystem();
});

$('paypalEmailForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    try {
        const profile = await apiJson('/api/profile/paypal-email', {
            method: 'PUT',
            headers: { 'Content-Type': 'application/json' },
            body: JSON.stringify({ email: $('paypalEmailInput').value.trim() })
        }, 20000);

        me.paypalEmail = profile.paypalEmail || '';

        showToast(me.paypalEmail ? 'PayPal email saved.' : 'PayPal email removed.');

        await loadPayments();

        renderPaypalSystem();

    } catch (error) {
        showToast(error.message);
    }
});

/* ---------- Home Expense Guardian ---------- */

const HOME_STATUS = { PAID: 'Paid', DUE_TODAY: 'Due today', OVERDUE: 'Overdue', UPCOMING: 'Upcoming' };

function thisMonth() {
    return today().slice(0, 7);
}

function shiftMonth(month, by) {
    const [y, m] = month.split('-').map(Number);

    const d = new Date(y, m - 1 + by, 1);

    return d.getFullYear() + '-' + String(d.getMonth() + 1).padStart(2, '0');
}

async function loadHome() {
    if (!me.email) {
        return;
    }

    if (!homeMonth) {
        homeMonth = thisMonth();
    }

    try {
        [homeData, homeReport] = await Promise.all([
            apiJson('/api/home/overview', {}, 15000),
            apiJson('/api/home/report?month=' + homeMonth, {}, 15000)
        ]);

    } catch (error) {
        return;
    }

    renderHome();
}

function statBox(label, value, color) {
    const stat = makeElement('div', 'stat');

    stat.appendChild(makeElement('div', 'l', label));

    const v = makeElement('div', 'v', value);

    if (color) {
        v.style.color = color;
    }

    stat.appendChild(v);

    return stat;
}

function renderHome() {
    if (!homeData) {
        return;
    }

    const s = homeData.summary;

    $('homeSummary').replaceChildren(
        statBox('Due today', String(s.dueCount)),
        statBox('Paid today', s.paidCount + ' • ' + money(s.paidAmount), 'var(--green)'),
        statBox('Pending today', s.pendingCount + ' • ' + money(s.pendingAmount), s.pendingCount ? 'var(--amber)' : ''),
        statBox('Overdue', s.overdueCount + ' • ' + money(s.overdueAmount), s.overdueCount ? 'var(--red)' : '')
    );

    const select = $('homeCategory');

    if (!select.options.length) {
        safeArray(homeData.categories).forEach(c => select.appendChild(new Option(c, c)));
    }

    if (!$('homeStart').value) {
        $('homeStart').value = today();
    }

    renderRecords('homeDue', safeArray(homeData.due),
        'Nothing to do. You are all caught up. 🎉', row => {

        const item = makeElement('div', 'record-item');

        const left = makeElement('div');

        left.appendChild(makeElement('strong', '', row.name + ' • ' + money(row.amount)));

        left.appendChild(makeElement('div', 'sub',
            displayDate(row.dueDate) + ' • ' + row.category + ' • ' + String(row.frequency).toLowerCase()));

        const actions = makeElement('div', 'row-actions');

        const paid = makeElement('button', '', 'Mark paid');

        paid.type = 'button';

        paid.title = 'Records that you paid this yourself. It is not verified by PayPal.';

        paid.addEventListener('click', () => markHomePaid(row));

        actions.appendChild(paid);

        if (row.recipientPhone) {
            const pay = makeElement('button', 'btn-gold', 'Pay with Monetra');

            pay.type = 'button';

            pay.addEventListener('click', () => payHomeExpense(row));

            actions.appendChild(pay);
        }

        left.appendChild(actions);

        item.appendChild(left);

        item.appendChild(makeElement('span', 'badge ' + row.status.toLowerCase(), HOME_STATUS[row.status] || row.status));

        return item;
    });

    renderHomeReport();

    renderRecords('homeAll', safeArray(homeData.expenses), 'No household expenses yet. Add one above.', e => {
        const card = recordCard(
            e.name,
            money(e.amount) + ' • ' + String(e.frequency).toLowerCase() + ' from ' + displayDate(e.startDate) + ' • ' + e.category,
            '',
            'Delete',
            () => deleteHomeExpense(e),
            '🏠'
        );

        return card;
    });
}

function renderHomeReport() {
    $('homeMonthLabel').textContent = new Date(homeMonth + '-01T00:00:00')
        .toLocaleDateString('en-IN', { month: 'long', year: 'numeric' });

    const box = $('homeReport');

    box.replaceChildren();

    if (!homeReport || !safeArray(homeReport.categories).length) {
        box.appendChild(makeElement('div', 'empty-state', 'Nothing scheduled in this month.'));
        return;
    }

    const scroller = makeElement('div', 'table-wrap');
    const table = makeElement('table', 'table');

    table.style.minWidth = '300px';

    const head = document.createElement('tr');

    ['Category', 'Paid', 'Outstanding'].forEach(h => head.appendChild(makeElement('th', '', h)));

    const thead = document.createElement('thead');

    thead.appendChild(head);
    table.appendChild(thead);

    const tbody = document.createElement('tbody');

    safeArray(homeReport.categories).forEach(c => {
        const tr = document.createElement('tr');

        tr.appendChild(makeElement('td', '', c.category));
        tr.appendChild(makeElement('td', 'num income', money(c.paid)));
        tr.appendChild(makeElement('td', 'num ' + (Number(c.outstanding) ? 'expense' : ''), money(c.outstanding)));

        tbody.appendChild(tr);
    });

    const total = document.createElement('tr');

    total.appendChild(makeElement('td', '', 'Total'));
    total.appendChild(makeElement('td', 'num income', money(homeReport.totalPaid)));
    total.appendChild(makeElement('td', 'num ' + (Number(homeReport.totalOutstanding) ? 'expense' : ''),
        money(homeReport.totalOutstanding)));

    tbody.appendChild(total);

    table.appendChild(tbody);
    scroller.appendChild(table);
    box.appendChild(scroller);

    /* What was paid, and who says so. Manual entries can be undone; PayPal ones cannot. */

    const paidRows = safeArray(homeReport.rows).filter(r => r.status === 'PAID');

    if (paidRows.length) {
        const list = makeElement('div', 'record-list');

        list.style.marginTop = '10px';

        paidRows.forEach(r => {
            const item = makeElement('div', 'record-item');

            const left = makeElement('div');

            left.appendChild(makeElement('strong', '', r.name + ' • ' + money(r.amount)));

            left.appendChild(makeElement('div', 'sub', displayDate(r.dueDate) + ' • ' +
                (r.paidSource === 'PAYPAL' ? 'Confirmed through PayPal' : 'Marked paid by you (not verified)')));

            item.appendChild(left);

            if (r.paidSource === 'MANUAL') {
                const undo = makeElement('button', 'small-button', 'Undo');

                undo.type = 'button';

                undo.addEventListener('click', () => undoHomePaid(r));

                item.appendChild(undo);
            }

            list.appendChild(item);
        });

        box.appendChild(list);
    }
}

async function markHomePaid(row) {
    try {
        await postJson('/api/home/expenses/' + row.expenseId + '/paid', { dueDate: row.dueDate }, 15000);

        showToast('Marked as paid. This is recorded by you, not verified by PayPal.');

        await loadHome();

        updateGuardianBadge();

    } catch (error) {
        showToast(error.message);
    }
}

async function undoHomePaid(row) {
    try {
        await apiJson('/api/home/expenses/' + row.expenseId + '/paid?dueDate=' + row.dueDate,
            { method: 'DELETE' }, 15000);

        await loadHome();

        updateGuardianBadge();

    } catch (error) {
        showToast(error.message);
    }
}

async function deleteHomeExpense(expense) {
    if (!window.confirm('Delete "' + expense.name + '" and its history?')) {
        return;
    }

    try {
        await apiJson('/api/home/expenses/' + expense.id, { method: 'DELETE' }, 15000);

        await loadHome();

        updateGuardianBadge();

    } catch (error) {
        showToast(error.message);
    }
}

/* Pays a household expense through Monetra. When PayPal confirms it, it is marked paid by itself. */

function payHomeExpense(row) {
    navigateTo('paymentView', 'send');

    resetPayForm();

    setMethod('PHONE');

    $('payPhone').value = row.recipientPhone;
    $('payAmount').value = Number(row.amount);
    $('payNote').value = row.name;

    $('phoneBox').classList.remove('hidden');

    payContext = { homeExpenseId: row.expenseId, homeDueDate: row.dueDate };

    $('paymentMessage').textContent =
        'Paying ' + row.name + ' (due ' + displayDate(row.dueDate) + '). It is marked paid once PayPal confirms it.';

    lookupRecipient();
}

$('homeForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    try {
        await postJson('/api/home/expenses', {
            name: $('homeName').value.trim(),
            amount: $('homeAmount').value,
            category: $('homeCategory').value,
            frequency: $('homeFrequency').value,
            startDate: $('homeStart').value,
            remindDaysBefore: $('homeRemind').value,
            recipientPhone: $('homePhone').value.trim()
        }, 15000);

        this.reset();

        $('homeStart').value = today();

        $('homeAddFold').open = false;

        showToast('Expense saved. You will be reminded before it is due.');

        await loadHome();

        updateGuardianBadge();

    } catch (error) {
        showToast(error.message);
    }
});

$('homePrevMonth').addEventListener('click', () => {
    homeMonth = shiftMonth(homeMonth || thisMonth(), -1);
    loadHome();
});

$('homeNextMonth').addEventListener('click', () => {
    homeMonth = shiftMonth(homeMonth || thisMonth(), 1);
    loadHome();
});

/* ==============================
   INITIAL PAGE
   Also handles the return from PayPal.
================================ */

async function init() {
    showPage('loginPage');

    try {
        const remembered = localStorage.getItem(REMEMBER_KEY);

        if (remembered) {
            $('loginEmail').value = remembered;
        }
    } catch (error) {
        /* Storage unavailable. */
    }

    loadPaymentConfig();

    const params = new URLSearchParams(location.search);

    const returning = !!params.get('paypal');

    /* The server session tells us who is logged in, even after a refresh. */

    let profile = null;

    try {
        profile = await apiJson('/api/auth/me', {}, 8000);

    } catch (error) {
        profile = null;
    }

    if (returning) {
        /* Remove the PayPal parameters so a refresh cannot repeat the request. */

        history.replaceState(null, '', location.pathname);
    }

    if (!profile || !profile.email) {
        if (returning) {
            $('loginMessage').textContent = 'Sign in again to see the result of your PayPal payment.';
        }

        return;
    }

    await openDashboard(profile);

    if (returning) {
        await handlePaypalReturn(params);
    }
}

init();
