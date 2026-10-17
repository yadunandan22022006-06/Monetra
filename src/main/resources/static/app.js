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
    ALERT: '🛡️',
    SCORE_DAILY: '🛡️',
    BADGE_EARNED: '🏅',
    DAY_BADGE: '🛡️',
    WEEK_BADGE: '🏆'
};

/* A short title for each kind of notification. */

const NOTIFICATION_TITLES = {
    PAYMENT_RECEIVED: 'Money received',
    PAYMENT_SENT: 'Payment sent',
    PAYMENT_FAILED: 'Payment not completed',
    PAYOUT: 'Payout',
    REMINDER: 'Reminder',
    ALERT: 'Guardian alert',
    SCORE_DAILY: 'Daily Guardian Update',
    BADGE_EARNED: '🎉 New badge earned',
    DAY_BADGE: '🎉 Good Day badge',
    WEEK_BADGE: '🎉 Good Week badge'
};

/* Notifications that open the Guardian Score screen. */

const SCORE_NOTIFICATIONS = ['SCORE_DAILY', 'BADGE_EARNED', 'DAY_BADGE', 'WEEK_BADGE'];

/* Where each Guardian Score notification opens: the score itself, or its badges. */

function openScoreFor(type) {
    if (type === 'DAY_BADGE') {
        openScore('badges', 'daily');

    } else if (type === 'WEEK_BADGE') {
        openScore('badges', 'weekly');

    } else if (type === 'BADGE_EARNED') {
        openScore('badges', 'levels');

    } else {
        openScore();
    }
}

const PARENT_VIEW = {
    forecastView: 'homeView',
    fraudView: 'homeView',
    notifView: 'homeView',
    transactionView: 'homeView',
    guardianView: 'none',
    heView: 'none',
    bankView: 'homeView',
    scoreView: 'homeView',
    settingsView: 'none'
};

let me = { email: '', name: '', phone: '', paypalEmail: '' };
let payConfig = { paypalConfigured: false, webhookConfigured: false };
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
let viewHistory = [];           /* the screens visited before this one, for Back */
let goingBack = false;
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

let overview = null;            /* the Guardian's figures for the dashboard, loans and goals, from the server */
let editingLoan = null;         /* id of the loan being edited, if any */
let editingGoal = null;
let heType = 'DAILY';           /* the home expense (HE) payment option that is open */
let heHistory = [];

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
        dailyLimit: 0,
        profile: {},                /* balance, monthly income, income day, essentials, buffer, notify */
        he: { reminders: {} }       /* home expense reminders */
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

/*
 * Questions are asked in a box drawn by the app itself. The browser's own confirm() and prompt()
 * are never shown inside some app windows: they answer "Cancel" by themselves.
 */

let askDone = null;

function askBox(message, yesText, noText, startValue, cards, extras) {
    const input = $('askInput');
    const wantsText = startValue !== undefined;

    if (askDone) {
        askDone(false);
    }

    /* Red warning boxes, when the question comes with some (see guardianAlertCards). */

    const cardsBox = $('askCards');

    cardsBox.replaceChildren();

    if (cards) {
        cardsBox.appendChild(cards);
    }

    cardsBox.classList.toggle('hidden', !cards);

    /* The Pay review's four buttons: with `extras`, the answer can also be 'adjust' or 'budget'. */

    $('askAdjust').classList.toggle('hidden', !extras);
    $('askBudget').classList.toggle('hidden', !extras);
    $('askButtons').classList.toggle('ask-four', !!extras);

    $('askText').textContent = message;
    $('askYes').textContent = yesText || 'OK';
    $('askNo').textContent = noText || 'Cancel';

    input.classList.toggle('hidden', !wantsText);
    input.value = wantsText ? String(startValue) : '';

    $('askBox').classList.remove('hidden');

    /* A tall box starts at the top (the red boxes), not scrolled down to the focused button. */

    $('askBox').querySelector('.ask-card').scrollTop = 0;

    (wantsText ? input : $('askYes')).focus({ preventScroll: true });

    return new Promise(resolve => {
        askDone = yes => {
            askDone = null;

            $('askBox').classList.add('hidden');

            resolve(wantsText ? (yes ? input.value : null) : yes);
        };
    });
}

/* true when the user agrees, false when they do not. */

function askConfirm(message, yesText, noText, cards, extras) {
    return askBox(message, yesText, noText, undefined, cards, extras);
}

/* What the user typed, or null when they cancelled. */

function askText(message, startValue) {
    return askBox(message, 'OK', 'Cancel', startValue === undefined || startValue === null ? '' : startValue);
}

$('askYes').addEventListener('click', () => askDone && askDone(true));
$('askNo').addEventListener('click', () => askDone && askDone(false));
$('askAdjust').addEventListener('click', () => askDone && askDone('adjust'));
$('askBudget').addEventListener('click', () => askDone && askDone('budget'));

$('askBox').addEventListener('keydown', function(event) {
    if (event.key === 'Escape') {
        $('askNo').click();

    } else if (event.key === 'Enter' && event.target === $('askInput')) {
        event.preventDefault();

        $('askYes').click();
    }
});

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

    const isObject = value => value && typeof value === 'object' && !Array.isArray(value);

    records.profile = isObject(records.profile) ? records.profile : {};
    records.profile.notify = isObject(records.profile.notify) ? records.profile.notify : {};

    records.he = isObject(records.he) ? records.he : {};
    records.he.reminders = isObject(records.he.reminders) ? records.he.reminders : {};
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

            /* The Guardian's figures are worked out on the server from what was just saved. */

            if (run === saveRun) {
                loadOverview();
            }

        } catch (error) {
            if (run === saveRun) {
                showToast('Could not save your data: ' + error.message);
            }
        }
    }, 250);
}

/* Daily budget, loans, next month and goals, calculated by the server from the saved records. */

async function loadOverview() {
    if (!me.email) {
        return;
    }

    try {
        overview = await apiJson('/api/guardian/overview', {}, 15000);

    } catch (error) {
        return;     /* the pages keep showing the last figures */
    }

    renderAwareness();

    loadScore();

    queueBand();
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

    adoptAccountPrefs();

    renderAll();

    navigateTo('homeView');

    loadOverview();

    await loadPayments();

    loadHe();
    loadNotifications(false);
    loadForecast(false);
    loadGuardianBadge();
    warmAi();

    clearInterval(pollTimer);

    pollTimer = setInterval(pollNotifications, POLL_MS);

    startBankUpdates();
}

function closeDashboard(note) {
    viewHistory = [];
    guardianScore = null;
    scoreZoneSent = false;

    if (me.email) {
        fetch('/api/auth/logout', { method: 'POST' }).catch(function() {
            /* The session also expires on its own. */
        });
    }

    stopScan();

    clearInterval(pollTimer);
    clearTimeout(bankTimer);

    bankAccounts = [];
    bankChosen = null;

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
    overview = null;
    heHistory = [];
    editingLoan = null;
    editingGoal = null;
    homeData = null;
    homeReport = null;
    chatHistory = [];
    maxSeenNotification = -1;

    resetPayForm();

    $('agentLog').replaceChildren(makeElement('div', 'bubble bot',
        'Ask about your money, or tap a question above.'));

    $('paymentResult').classList.add('hidden');
    $('heResult').classList.add('hidden');
    $('bellBadge').classList.add('hidden');
    $('guardianBadge').classList.add('hidden');

    heDrafts = {};
    heBill = null;
    heMethod = 'MOBILE';
    heMethodInfo = {};
    hePayee = null;
    heScanned = false;
    heOpenRow = '';

    stopLoanEdit();
    stopGoalEdit();

    $('heForm').reset();
    $('heRecipientCard').classList.add('hidden');
    $('heContactList').classList.add('hidden');

    $('loginEmail').focus();
}

/* ==============================
   NAVIGATION (bottom bar)
================================ */

/* Wakes up an AI that runs on this computer, so its first notice is ready by the time you review a payment. */
function warmAi() {
    postJson('/api/guardian/warm', {}, 8000).catch(() => {});
}

function navigateTo(viewId, action) {
    const target = $(viewId);

    if (!target) {
        return;
    }

    if (!goingBack && viewId !== currentView && $(currentView)) {
        viewHistory.push(currentView);

        if (viewHistory.length > 30) {
            viewHistory.shift();
        }
    }

    if (currentView === 'paymentView' && viewId !== 'paymentView') {
        stopScan();
    }

    if (viewId === 'paymentView') {
        warmAi();
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

    /* The page itself never scrolls: only the open panel does, and it starts at the top. */

    target.querySelectorAll('.tab-panel').forEach(panel => {
        panel.scrollTop = 0;
    });

    if (viewId === 'paymentView' && !goingBack) {
        showPayTab(action === 'receive' ? 'receive' : 'send');
    }

    if (viewId === 'scoreView') {
        renderScore();
        loadScore();
    }

    queueBand();

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
        renderMoney();
    }

    if (viewId === 'guardianView') {
        loadGuardian();
    }

    if (viewId === 'bankView') {
        loadBank();
    }

    if (viewId === 'heView') {
        renderHe();
        loadHeMethods();
        loadHe();

    } else {
        heBill = null;

        if (scanner === SCANNERS.he) {
            stopScan();
        }
    }

    if (viewId === 'homeView' || viewId === 'loanView' || viewId === 'goalView') {
        loadOverview();
    }
}

document.querySelectorAll('#bottomNav button').forEach(button => {
    button.addEventListener('click', () => navigateTo(button.dataset.view));
});

/* The navy band reaches just below each screen's title and tabs; on Home, behind most of the Safe to Spend card. */

function placeBand() {
    const page = $('dashboardPage');
    const view = $(currentView);

    if (!page || !view || page.classList.contains('hidden')) {
        return;
    }

    const top = page.getBoundingClientRect().top;
    const hero = view.querySelector('.home-hero');

    let bottom;

    if (hero && hero.offsetParent) {
        const card = hero.getBoundingClientRect();

        bottom = card.bottom - Math.min(44, card.height * 0.25);

    } else {
        const heads = [...view.querySelectorAll(':scope > .page-head, :scope > .subtabs, :scope > .seg')]
            .filter(e => e.offsetParent);

        const last = heads[heads.length - 1];

        bottom = (last ? last.getBoundingClientRect().bottom : top + 110) + 18;
    }

    page.style.setProperty('--band', Math.max(90, Math.round(bottom - top)) + 'px');
}

function queueBand() {
    requestAnimationFrame(placeBand);
    setTimeout(placeBand, 320);
}

window.addEventListener('resize', queueBand);

/*
 * Back: the screen before this one, or Home when there is none. The screens stay as they were,
 * with what was typed in them, and nothing is sent again: going back only changes the screen.
 */

function goBack() {
    let target = 'homeView';

    while (viewHistory.length) {
        const previous = viewHistory.pop();

        if (previous !== currentView && $(previous)) {
            target = previous;
            break;
        }
    }

    goingBack = true;

    try {
        navigateTo(target);

    } finally {
        goingBack = false;
    }
}

/* Tabs inside a screen: each screen shows one panel at a time, so nothing runs off the bottom. */

function showSubtab(group, tab) {
    document.querySelectorAll('.subtabs[data-group="' + group + '"] button').forEach(button => {
        const active = button.dataset.tab === tab;

        button.classList.toggle('active', active);
        button.setAttribute('aria-selected', active ? 'true' : 'false');
    });

    document.querySelectorAll('.tab-panel[data-group="' + group + '"]').forEach(panel => {
        panel.classList.toggle('hidden', panel.dataset.tab !== tab);

        panel.scrollTop = 0;
    });

    if (group === 'he' && tab !== 'pay') {
        stopScan();
    }

    /* The PayPal list shows each payment's latest status whenever it is opened. */
    if (group === 'gai' && tab === 'paypal') {
        loadPayments();
    }

    queueBand();
}

document.querySelectorAll('.subtabs').forEach(bar => {
    bar.querySelectorAll('button').forEach(button => {
        button.addEventListener('click', () => showSubtab(bar.dataset.group, button.dataset.tab));
    });
});

/* ==============================
   RECORDS HELPERS
================================ */

/* Everyday spending in a date range. An EMI marked as paid is tracked under Loans, so it does not use up a limit. */

function spentBetween(fromKey, toKey) {
    return records.expenses
        .filter(item => item.source !== 'emi' && item.date >= fromKey && item.date <= toKey)
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
        'No money received yet.',
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

function renderRecipientCard(info, message, state, cardId) {
    const card = $(cardId || 'recipientCard');

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

    suggestContacts(CONTACT_BOXES.pay);

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

/* The two places where a person is chosen. Each has a box to type in and a list that opens under it. */

const CONTACT_BOXES = {
    pay: { input: 'payPhone', field: 'contactField', list: 'contactList', pick: contact => pickContact(contact) },
    he: { input: 'hePhone', field: 'hePhoneField', list: 'heContactList', pick: contact => pickHeContact(contact) }
};

let contactBox = CONTACT_BOXES.pay;     /* the box used last, so the list can be redrawn after an import */

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

/* What is typed in a contact box, as a search: the digits of a number, or the letters of a name. */

function contactQuery(box) {
    const text = $(box.input).value.trim();
    const digits = text.replace(/\D/g, '');

    const number = text !== '' && /^[+\d\s().-]*$/.test(text);

    return { text: number ? digits : text, number, complete: number && digits.length >= 10 };
}

/*
 * Like a phone's dialler: as you type a name or the first digits of a number, the people in your
 * contacts who match are listed. A complete number needs no list; its owner's name is looked up.
 */

function suggestContacts(box) {
    const query = contactQuery(box);

    clearTimeout(contactTimer);

    if (query.complete) {
        $(box.list).classList.add('hidden');
        return;
    }

    contactTimer = setTimeout(() => loadContacts(box), 200);
}

async function loadContacts(box) {
    contactBox = box || contactBox;

    const list = $(contactBox.list);
    const query = contactQuery(contactBox);
    const shownFor = contactBox;

    try {
        const found = safeArray(await apiJson('/api/contacts?q=' + encodeURIComponent(query.text), {}, 8000));

        /* The box may have changed while the server was answering. */

        if (shownFor !== contactBox || contactQuery(shownFor).text !== query.text) {
            return;
        }

        list.replaceChildren(contactTools());

        if (!found.length) {
            list.appendChild(makeElement('div', 'empty-state', query.text
                ? (query.number ? 'No contact has that number. Keep typing the full mobile number.'
                    : 'No contact has that name. Type the mobile number instead.')
                : 'No contacts yet.'));
        }

        found.forEach(c => {
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

            item.addEventListener('click', () => {
                list.classList.add('hidden');

                shownFor.pick(c);
            });

            list.appendChild(item);
        });

        list.classList.remove('hidden');

    } catch (error) {
        list.classList.add('hidden');
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

    loadContacts(contactBox);
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
    if (!await askConfirm('Remove all contacts that were added from your phone? Contacts you saved yourself stay.', 'Remove')) {
        return;
    }

    try {
        const result = await apiJson('/api/contacts/imported', { method: 'DELETE' }, 15000);

        showToast(result.removed
            ? 'Removed ' + result.removed + ' phone contact' + (result.removed === 1 ? '' : 's') + '.'
            : 'There were no phone contacts to remove.');

        loadContacts(contactBox);

    } catch (error) {
        showToast(error.message);
    }
}

function pickContact(contact) {
    $('contactList').classList.add('hidden');

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
        label = ((await askText('Name for this contact (so you can find them later):', scannedName || '')) || '').trim();

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

/* Opening a box shows your contacts; clicking anywhere else closes the list. */

Object.values(CONTACT_BOXES).forEach(box => {
    $(box.input).addEventListener('focus', function() {
        if (!contactQuery(box).complete) {
            loadContacts(box);
        }
    });
});

document.addEventListener('click', function(event) {
    Object.values(CONTACT_BOXES).forEach(box => {
        if (!$(box.field).contains(event.target) && !$(box.list).contains(event.target)) {
            $(box.list).classList.add('hidden');
        }
    });
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

/* Where the camera, its button and its messages are. Pay and HE each have their own. */

const SCANNERS = {
    pay: { video: 'scanVideo', box: 'scannerBox', start: 'scanStart', message: 'scanMessage', read: info => applyScan(info) },
    he: { video: 'heScanVideo', box: 'heScannerBox', start: 'heScanStart', message: 'heScanMessage', read: info => applyHeScan(info) }
};

let scanner = SCANNERS.pay;

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

async function startScan(which) {
    stopScan();

    scanner = which || SCANNERS.pay;

    if (!('BarcodeDetector' in window) || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        $(scanner.message).textContent =
            'Live scanning is not supported in this browser. Use "Choose QR image" to upload a photo or screenshot of the code.';
        return;
    }

    try {
        scanStream = await navigator.mediaDevices.getUserMedia({
            video: { facingMode: { ideal: 'environment' } },
            audio: false
        });

    } catch (error) {
        $(scanner.message).textContent =
            'Camera permission was not given. Allow the camera, or choose a QR image instead.';
        return;
    }

    const video = $(scanner.video);

    video.srcObject = scanStream;

    await video.play().catch(function() { /* Autoplay can be blocked; the stream still attaches. */ });

    $(scanner.box).classList.remove('hidden');
    $(scanner.start).textContent = 'Stop camera';
    $(scanner.message).textContent = 'Point the camera at the QR code...';

    const detector = new BarcodeDetector({ formats: ['qr_code'] });

    scanTimer = setInterval(async function() {
        try {
            const found = await detector.detect(video);

            if (found.length) {
                const ok = scanner.read(parseQr(found[0].rawValue));

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

    Object.values(SCANNERS).forEach(s => {
        $(s.box).classList.add('hidden');

        $(s.start).textContent = 'Open camera';
    });
}

/* Reads a QR code from a photo or screenshot, on the server. */

async function readQrImage(file, which) {
    if (!file) {
        return;
    }

    $(which.message).textContent = 'Reading the QR code...';

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

        which.read(parseQr(data.text));

    } catch (error) {
        $(which.message).textContent = error.message;
    }
}

[['pay', 'scanStart', 'scanUpload', 'scanFile'], ['he', 'heScanStart', 'heScanUpload', 'heScanFile']].forEach(ids => {
    const which = SCANNERS[ids[0]];

    $(ids[1]).addEventListener('click', function() {
        if (scanStream && scanner === which) {
            stopScan();
        } else {
            startScan(which);
        }
    });

    $(ids[2]).addEventListener('click', () => $(ids[3]).click());

    $(ids[3]).addEventListener('change', function() {
        const file = this.files && this.files[0];

        this.value = '';

        readQrImage(file, which);
    });
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
    $('reviewGuardian').replaceChildren();

    $('continuePaypal').disabled = false;
}

const PERIOD_NAMES = { daily: 'daily', weekly: 'weekly (7-day)', monthly: 'monthly', yearly: 'yearly' };

/* Lines for the limits a payment respects. A broken limit is shown in the Guardian's red boxes instead. */

function budgetLines(check) {
    const lines = [];

    safeArray(check.periods).forEach(p => {
        const name = PERIOD_NAMES[p.period] || p.period;
        const after = Number(p.remainingAfter);

        if (p.level === 'low') {
            lines.push({ warn: true, text: 'It fits your ' + name + ' limit but leaves only ' +
                money(after) + ' of ' + money(p.limit) + '.' });

        } else if (p.level !== 'over') {
            lines.push({ text: 'It fits your ' + name + ' limit and leaves ' + money(after) + '.' });
        }
    });

    return lines;
}

const RISK_CLASS = { HIGH: 'high', MEDIUM: 'medium', LOW: 'low', UNKNOWN: 'unknown' };

/* A small coloured label such as "Daily budget: High". */

function riskChip(text, level) {
    return makeElement('span', 'risk-chip ' + (RISK_CLASS[level] || 'unknown'), text);
}

/* One numbered red box: what is wrong, and what the user could do about it. */

function alertBox(index, title, text, action) {
    const item = makeElement('div', 'alert-card');

    const head = makeElement('div', 'alert-card-head');

    /* ①, ②, ③ ... */
    head.appendChild(makeElement('span', 'alert-badge', String.fromCharCode(0x2460 + Math.min(index, 19))));
    head.appendChild(makeElement('span', 'alert-card-title', title));
    head.appendChild(makeElement('span', 'alert-icon', '⚠'));

    item.appendChild(head);

    const body = makeElement('p', '', text);

    item.appendChild(body);

    if (action) {
        item.appendChild(makeElement('p', 'alert-action', '👉 ' + action));
    }

    return { item, body };
}

/* The Fraud Guard's reasons, as one more red box, with the tick the user needs to give to continue. */

function fraudBox(index, risk) {
    const fraud = alertBox(index, 'FRAUD GUARD', 'Please check this payment.', '');

    const list = makeElement('ul', 'alert-list');

    safeArray(risk.flags).forEach(text => list.appendChild(makeElement('li', '', text)));

    fraud.item.appendChild(list);

    const label = makeElement('label', 'check-row');

    const tick = document.createElement('input');

    tick.type = 'checkbox';
    tick.id = 'riskConfirm';

    tick.addEventListener('change', () => {
        label.classList.remove('needs-tick');

        if (tick.checked) {
            $('reviewMessage').textContent = '';
        }
    });

    label.appendChild(tick);
    label.appendChild(document.createTextNode('I know this recipient and want to continue with this payment.'));

    fraud.item.appendChild(label);

    return fraud.item;
}

/*
 * The same numbered red boxes as the Pay review, for the question box (used by HE): one box for each thing the
 * Guardian found, shown once, then the Fraud Guard's reasons. Returns null when nothing needs a warning.
 */

function guardianAlertCards(check, risk) {
    const found = [];
    const seen = new Set();

    let decision = null;

    safeArray(check && check.guardian && check.guardian.cards).forEach(card => {
        const key = card.title + '|' + card.text;

        if (card.title === 'YOUR DECISION') {
            decision = card;

        } else if (!seen.has(key)) {
            seen.add(key);
            found.push(card);
        }
    });

    const flags = safeArray(risk && risk.flags);

    if (!found.length && !flags.length) {
        return null;
    }

    const wrap = makeElement('div', 'ask-cards-list');

    wrap.appendChild(makeElement('div', 'alert-title', '🔴 GUARDIAN AI — PAYMENT ALERT'));

    let n = 0;

    found.forEach(card => wrap.appendChild(alertBox(n++, card.title, card.text, card.action).item));

    if (flags.length) {
        const fraud = alertBox(n++, 'FRAUD GUARD', 'Please check this payment.', '');
        const list = makeElement('ul', 'alert-list');

        flags.forEach(text => list.appendChild(makeElement('li', '', text)));

        fraud.item.appendChild(list);
        wrap.appendChild(fraud.item);
    }

    /* Last, as on the Pay review: the decision. */

    wrap.appendChild(alertBox(n, 'YOUR DECISION',
        decision ? decision.text : 'Review the payment and decide whether to continue.', '').item);

    return wrap;
}

/* The server asked for the Fraud Guard to be confirmed after all (it checks again when the order is made). */

function showFraudBox(risk) {
    const box = $('reviewFraud');

    box.replaceChildren();

    if (risk.flagged && !$('riskConfirm')) {
        box.appendChild(fraudBox(document.querySelectorAll('#reviewGuardian .alert-card').length, risk));
    }
}

/*
 * Guardian AI on the review screen. When anything needs a warning, everything the user should know
 * is one numbered red box each, shown once: what the server found, the limits, the Fraud Guard, and
 * last YOUR DECISION, where the AI's advice goes. Returns that box's text, or null when nothing
 * needs a warning (then the review shows plain green notes instead).
 */

function renderGuardianReview(guardian, extras, risk) {
    const box = $('reviewGuardian');

    box.replaceChildren();

    const impacts = safeArray(guardian && guardian.impacts);

    if (impacts.length) {
        const strip = makeElement('div', 'impact-strip');

        impacts.forEach(i => strip.appendChild(riskChip(i.area + ': ' + i.label, i.level)));

        strip.appendChild(riskChip(guardian.overallLabel, guardian.overall));

        strip.lastChild.classList.add('overall');

        box.appendChild(strip);
    }

    /* The server's boxes. Its decision box always goes last, and no box is ever shown twice. */

    const seen = new Set();
    const items = [];

    let decision = null;

    safeArray(guardian && guardian.cards).forEach(card => {
        if (card.title === 'YOUR DECISION') {
            decision = card;
            return;
        }

        const key = card.title + '|' + card.text;

        if (!seen.has(key)) {
            seen.add(key);
            items.push(card);
        }
    });

    const flagged = !!(risk && risk.flagged);

    if (!items.length && !flagged && !extras.some(e => e.warn)) {
        return null;
    }

    extras.forEach(e => items.push(e));

    box.appendChild(makeElement('div', 'alert-title', '🔴 GUARDIAN AI — PAYMENT ALERT'));

    let n = 0;

    items.forEach(card => box.appendChild(alertBox(n++, card.title, card.text, card.action).item));

    if (flagged) {
        box.appendChild(fraudBox(n++, risk));
    }

    const last = alertBox(n, 'YOUR DECISION',
        decision ? decision.text : 'Review the payment and decide whether to continue.', '');

    box.appendChild(last.item);

    return last.body;
}

async function startReview() {
    const message = $('paymentMessage');

    message.textContent = 'Checking your budget...';

    const run = ++reviewRun;

    let check;
    let risk;

    try {
        /* The server finds the recipient's name itself, so the whole draft goes to both checks. */

        [check, risk] = await Promise.all([
            postJson('/api/guardian/check', draft, 15000),
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

    const guardian = check.guardian || {};
    const lines = budgetLines(check);

    /* What the limits say, and what is fine. With a warning these become red boxes too; without one, green notes. */

    const extras = [];

    if (!safeArray(check.periods).length) {
        extras.push({
            title: 'SPENDING LIMITS',
            text: 'No spending limit is set, so Monetra cannot check this payment against a budget.',
            action: 'Set one in Spending.'
        });

    } else if (lines.length) {
        extras.push({ title: 'SPENDING LIMITS', text: lines.map(l => l.text).join(' '), warn: lines.some(l => l.warn) });
    }

    safeArray(guardian.notes).forEach(text => extras.push({ title: 'GOOD TO KNOW', text }));

    $('paymentForm').classList.add('hidden');
    $('reviewPanel').classList.remove('hidden');

    $('reviewFraud').replaceChildren();

    const decisionText = renderGuardianReview(guardian, extras, risk);
    const warn = !!decisionText;

    const continueButton = $('continuePaypal');

    /* Always pressable: with a Fraud Guard notice, pressing it first asks for the tick (see below). */
    continueButton.disabled = false;
    continueButton.textContent = warn ? 'Continue Anyway' : 'Continue to PayPal';

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

    /* A broken daily limit is already in a red box, so it is not said again here. */

    if (daily && daily.level !== 'over') {
        summary.appendChild(statBox('Left today after this', money(daily.remainingAfter), 'var(--green)'));
    }

    /* Without a warning: plain notes. */

    const rules = $('reviewRules');

    rules.replaceChildren();

    if (!warn) {
        extras.forEach(e => rules.appendChild(makeElement(
            'div',
            'alert-box ' + (e.title === 'GOOD TO KNOW' || e.action ? '' : 'good-box'),
            (e.title === 'GOOD TO KNOW' ? '🛡️ ' : e.action ? 'ℹ️ ' : '✅ ') + e.text + (e.action ? ' ' + e.action : '')
        )));
    }

    requestAiAdvice(decisionText, run);
}

/* The AI's advice. With a warning it goes in the YOUR DECISION box; the boxes above never depend on it. */

async function requestAiAdvice(decisionText, run) {
    const box = $('reviewAi');

    const waiting = makeElement('div', 'footer-note', '🛡️ The Guardian AI is writing its advice...');

    box.replaceChildren(waiting);

    try {
        /* The server works out the figures from the saved records. A model on this computer can be slow to start. */

        const result = await postJson('/api/guardian/explain', {
            amountInr: draft.amountInr,
            recipientName: draft.resolvedName,
            description: draft.description
        }, 90000);

        if (run !== reviewRun) {
            return;
        }

        const advice = '🛡️ Guardian AI: ' + String(result.advice || '').slice(0, 500);

        if (decisionText) {
            decisionText.textContent = advice;

            box.replaceChildren();

        } else {
            box.replaceChildren(makeElement('div', 'alert-box good-box', advice));
        }

    } catch (error) {
        /* No AI advice: everything it would say is already shown above. */

        if (run === reviewRun) {
            box.replaceChildren();
        }
    }
}

/* Back to the payment form, with everything still filled in. */

function backToPayForm(focusAmount) {
    reviewRun++;

    hideReview();

    $('paymentForm').classList.remove('hidden');

    if (focusAmount) {
        $('payAmount').focus();
        $('payAmount').select();
    }
}

$('editPayment').addEventListener('click', () => backToPayForm(false));

$('adjustAmount').addEventListener('click', () => backToPayForm(true));

$('reviewBudget').addEventListener('click', function() {
    backToPayForm(false);

    navigateTo('spendingView');
});

/* Sends the browser to PayPal's approval page, and nowhere else. */

function goToPaypal(approvalUrl) {
    const url = new URL(approvalUrl);

    if (url.protocol !== 'https:' || !/(^|\.)paypal\.com$/.test(url.hostname)) {
        throw new Error('The server returned an unexpected payment address.');
    }

    window.location.assign(url.toString());
}

$('continuePaypal').addEventListener('click', async function() {
    if (!draft) {
        return;
    }

    const button = this;
    const message = $('reviewMessage');
    const confirm = $('riskConfirm');

    /* A Fraud Guard notice needs the user's tick first: say so and show where, instead of doing nothing. */
    if (confirm && !confirm.checked) {
        const row = confirm.closest('label');

        message.textContent = 'Tick "I know this recipient" in the Fraud Guard box first.';

        row.classList.add('needs-tick');
        row.scrollIntoView({ block: 'center', behavior: 'smooth' });
        confirm.focus({ preventScroll: true });
        return;
    }

    button.disabled = true;

    message.textContent = 'Creating your PayPal Sandbox order...';

    try {
        const order = await postJson('/api/payments/create-order', {
            ...draft,
            confirmRisk: !!(confirm && confirm.checked)
        }, 30000);

        message.textContent = 'Opening PayPal...';

        goToPaypal(order.approvalUrl);

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

function showPaymentResult(text, kind, boxId) {
    const box = $(boxId || 'paymentResult');

    box.className = 'alert-box' + (kind ? ' ' + kind : '');

    box.textContent = text;
}

async function handlePaypalReturn(params) {
    const flow = params.get('paypal');
    const ref = params.get('ref') || '';
    const token = params.get('token') || '';

    navigateTo('paymentView');

    /* A home expense (HE) payment reports its result on the HE screen instead of Pay. */

    let he = false;
    let shown = null;

    const show = (text, kind) => {
        shown = { text, kind };

        showPaymentResult(text, kind);
    };

    show('Checking your payment with PayPal...', '');

    try {
        if (flow === 'cancel') {
            const cancelled = await postJson('/api/payments/' + encodeURIComponent(ref) + '/cancel', {}, 15000);

            he = !!(cancelled && cancelled.heType);

            show('Payment cancelled. No money was taken.', 'bad-box');

        } else if (flow === 'return' && token) {
            const payment = await postJson('/api/payments/capture/' + encodeURIComponent(token), {}, 30000);

            he = !!payment.heType;

            if (payment.status === 'COMPLETED') {
                show(
                    '✅ Payment completed and confirmed: ' + money(payment.amountInr) +
                    ' (' + usd(payment.amountUsd) + ') to ' + payment.counterparty +
                    '. Reference ' + String(payment.paymentId).slice(0, 8) + '.',
                    'good-box'
                );

            } else {
                show(
                    'The payment was not completed (status: ' +
                    (STATUS_LABELS[payment.status] || payment.status) + '). ' + (payment.message || ''),
                    'bad-box'
                );
            }

            /* Confirmed or failed: the chosen sound or vibration, once (the browser may not allow it yet). */
            notifyAlert();

        } else {
            show('This payment link is not valid.', 'bad-box');
        }

    } catch (error) {
        show(
            'Monetra could not confirm this payment: ' + error.message +
            ' Open Transactions and press Refresh to check it again.',
            'bad-box'
        );
    }

    if (he) {
        $('paymentResult').classList.add('hidden');

        navigateTo('heView');

        showPaymentResult(shown.text, shown.kind, 'heResult');
    }

    await loadPayments();

    loadHe();
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

    /* Home expenses (HE) have their own history, so they are not repeated here. */

    records.expenses.filter(e => e.source !== 'paypal' && e.source !== 'he').forEach(e => {
        const id = Number(e.id) || 0;
        const noon = new Date(e.date + 'T12:00:00').getTime();

        /* An id made from the moment it was added is its exact time, but only when it was added on that same day. */
        const sameDay = id > 1e12 && new Date(id).toDateString() === new Date(noon).toDateString();

        items.push({
            kind: 'EXPENSE',
            title: e.name,
            sub: displayDate(e.date) + ' • Recorded expense',
            amount: Number(e.amount),
            at: sameDay ? id : noon,
            seq: id
        });
    });

    /* Newest first; on the same moment, the one added last first. */
    items.sort((a, b) => (b.at - a.at) || ((b.seq || 0) - (a.seq || 0)));

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
        'No activity yet.', activityRow);

    $('historyNote').textContent =
        'Payments appear after PayPal confirms them.';
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

    renderRecords('paypalStatus', sent, 'No PayPal payments yet.', p => {
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

    loadHe();
    loadNotifications(false);
}

$('refreshPaypalButton').addEventListener('click', loadPayments);

/* ==============================
   FRAUD GUARD ALERTS
================================ */

function renderFraud() {
    const flagged = payments.filter(p => p.direction === 'SENT' && p.riskFlags);

    renderRecords('fraudList', flagged, 'All clear. 🎉', p => {
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

/* Unread notifications on each side of the notification screen. */

let unreadMoney = 0;
let unreadBadges = 0;

function countUnread(data) {
    unreadMoney = safeArray(data.items).filter(n => !n.seen).length;
    unreadBadges = safeArray(data.scoreItems).filter(n => !n.seen).length;

    [['notifCountMoney', unreadMoney], ['notifCountBadges', unreadBadges]].forEach(([id, count]) => {
        $(id).textContent = count > 9 ? '9+' : String(count);
        $(id).classList.toggle('hidden', !count);
    });
}

/* The bell opens the side with something new: the badges when only they are new. */

function openNotifications() {
    navigateTo('notifView');
    showSubtab('notif', unreadBadges > 0 && unreadMoney === 0 ? 'badges' : 'list');
}

function notificationRow(n) {
    const row = makeElement('div', 'record-item notif-item' + (n.seen ? '' : ' unread'));

    const left = makeElement('div', 'row');

    left.appendChild(makeElement(
        'span',
        'icon-chip ' + (n.type === 'PAYMENT_RECEIVED' ? 'in' : (n.type === 'PAYMENT_FAILED' ? 'out' : '')),
        NOTIFICATION_ICONS[n.type] || '↗'
    ));

    const text = makeElement('div');

    if (NOTIFICATION_TITLES[n.type]) {
        text.appendChild(makeElement('div', 'notif-title', NOTIFICATION_TITLES[n.type]));
    }

    text.appendChild(makeElement('strong', '', n.message));
    text.appendChild(makeElement('div', 'sub', displayDateTime(n.createdAt)));

    left.appendChild(text);

    row.appendChild(left);

    if (SCORE_NOTIFICATIONS.includes(n.type)) {
        row.classList.add('clickable');
        row.setAttribute('role', 'button');
        row.tabIndex = 0;

        row.addEventListener('click', () => openScoreFor(n.type));
        row.addEventListener('keydown', event => {
            if (event.key === 'Enter' || event.key === ' ') {
                event.preventDefault();
                openScoreFor(n.type);
            }
        });
    }

    return row;
}

async function loadNotifications(markRead) {
    if (!me.email) {
        return;
    }

    try {
        const data = await apiJson('/api/notifications', {}, 10000);

        const items = safeArray(data.items);
        const scoreItems = safeArray(data.scoreItems);

        [...items, ...scoreItems].forEach(n => {
            maxSeenNotification = Math.max(maxSeenNotification, Number(n.id));
        });

        setBell(Number(data.unread) || 0);
        countUnread(data);

        renderRecords('notifList', items, 'No notifications yet.', notificationRow);
        renderRecords('notifBadgeList', scoreItems, 'No badges yet.', notificationRow);

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

        const all = [...safeArray(data.items), ...safeArray(data.scoreItems)];

        const fresh = all
            .filter(n => Number(n.id) > maxSeenNotification && !n.seen)
            .sort((a, b) => Number(a.id) - Number(b.id));

        all.forEach(n => {
            maxSeenNotification = Math.max(maxSeenNotification, Number(n.id));
        });

        setBell(Number(data.unread) || 0);
        countUnread(data);

        if (fresh.length) {
            const latest = fresh[fresh.length - 1];

            /* One sound or buzz for everything that arrived together, never one per item. */
            notifyAlert();

            showToast('🔔 ' + (NOTIFICATION_TITLES[latest.type] ? NOTIFICATION_TITLES[latest.type] + ': ' : '') + latest.message, 7000);

            fresh.forEach(showOnDevice);

            if (fresh.some(n => SCORE_NOTIFICATIONS.includes(n.type))) {
                loadScore();
            }

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
        status.textContent = (spent > limit * 0.8 ? 'Almost there' : 'On track') + ' • ' + Math.round(spent / limit * 100) + '% used';
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
        const result = await postJson('/api/guardian/forecast/explain', {}, 90000);

        box.replaceChildren(makeElement('div', 'alert-box', '🛡️ Guardian AI: ' + String(result.advice || '').slice(0, 700)));

    } catch (error) {
        /* Say why: the AI could not be reached, or its answer did not match the figures and was not shown. */
        const mismatch = /did not match/i.test(String(error && error.message));

        box.replaceChildren(
            makeElement('div', 'alert-box', '🛡️ ' + forecastText(forecast)),
            makeElement('div', 'footer-note', mismatch
                ? 'The AI answer did not match your figures, so Monetra\'s own check is shown.'
                : 'AI is not available right now: this uses Monetra\'s rules.')
        );

    } finally {
        button.disabled = false;
    }
});

/* ==============================
   LOANS AND EMI
================================ */

const LOAN_FREQUENCY = { MONTHLY: 'monthly', WEEKLY: 'weekly', YEARLY: 'yearly' };

/* A number from an optional field: empty means "not given" and is saved as 0. */

function optionalNumber(id) {
    const text = $(id).value.trim();

    return text === '' ? 0 : Number(text);
}

function activeLoans() {
    return records.loans.filter(loan => loan.status !== 'CLOSED');
}

$('loanForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const loan = {
        name: $('loanName').value.trim(),
        amount: Number($('loanAmount').value),
        due: $('loanDue').value,
        original: optionalNumber('loanOriginal'),
        outstanding: optionalNumber('loanOutstanding'),
        rate: optionalNumber('loanRate'),
        installmentsLeft: Math.round(optionalNumber('loanInstallments')),
        frequency: $('loanFrequency').value,
        status: $('loanStatus').value,
        notes: $('loanNotes').value.trim()
    };

    if (!loan.name || !validAmount(loan.amount) || !loan.due) {
        return;
    }

    const amounts = [loan.original, loan.outstanding, loan.rate, loan.installmentsLeft];

    if (amounts.some(n => !Number.isFinite(n) || n < 0) || loan.original > 1000000000 ||
        loan.outstanding > 1000000000 || loan.rate > 100 || loan.installmentsLeft > 1200) {

        showToast('Check the loan amounts: they cannot be negative, and the interest rate is at most 100%.');
        return;
    }

    if (loan.original > 0 && loan.outstanding > loan.original) {
        showToast('The outstanding balance cannot be more than the original loan amount.');
        return;
    }

    const existing = records.loans.find(item => item.id === editingLoan);

    if (existing) {
        Object.assign(existing, loan);

        showToast('Loan updated.');

    } else {
        records.loans.unshift({ id: Date.now(), ...loan });

        showToast('Loan added.');
    }

    saveRecords();

    stopLoanEdit();

    renderAll();
});

function editLoan(loan) {
    editingLoan = loan.id;

    $('loanFormTitle').textContent = 'Edit Loan';
    $('loanFormTitle').classList.remove('hidden');
    $('loanSubmit').textContent = 'Save Changes';
    $('loanCancel').classList.remove('hidden');

    $('loanName').value = loan.name || '';
    $('loanAmount').value = loan.amount || '';
    $('loanDue').value = loan.due || '';
    $('loanOriginal').value = loan.original || '';
    $('loanOutstanding').value = loan.outstanding || '';
    $('loanRate').value = loan.rate || '';
    $('loanInstallments').value = loan.installmentsLeft || '';
    $('loanFrequency').value = LOAN_FREQUENCY[loan.frequency] ? loan.frequency : 'MONTHLY';
    $('loanStatus').value = loan.status === 'CLOSED' ? 'CLOSED' : 'ACTIVE';
    $('loanNotes').value = loan.notes || '';

    showSubtab('loan', 'add');

    $('loanName').focus();
}

function stopLoanEdit() {
    editingLoan = null;

    $('loanForm').reset();

    $('loanFormTitle').textContent = 'Add Loan / EMI';
    $('loanFormTitle').classList.add('hidden');
    $('loanSubmit').textContent = 'Add Loan';
    $('loanCancel').classList.add('hidden');
}

$('loanCancel').addEventListener('click', stopLoanEdit);

/* The date one payment period after a date ("2026-11-05" monthly -> "2026-12-05"). */

function nextDueDate(due, frequency) {
    const d = new Date(due + 'T00:00:00');

    if (Number.isNaN(d.getTime())) {
        return due;
    }

    if (frequency === 'WEEKLY') {
        d.setDate(d.getDate() + 7);

    } else if (frequency === 'YEARLY') {
        d.setFullYear(d.getFullYear() + 1);

    } else {
        const day = d.getDate();

        d.setDate(1);
        d.setMonth(d.getMonth() + 1);
        d.setDate(Math.min(day, new Date(d.getFullYear(), d.getMonth() + 1, 0).getDate()));
    }

    return dateKey(d);
}

/*
 * The user says they paid this EMI. The loan moves to its next due date, the outstanding balance
 * goes down by the part of the EMI that is not interest, and the EMI is recorded as an expense.
 */

async function markEmiPaid(loan) {
    if (!await askConfirm('Mark the EMI of ' + money(loan.amount) + ' for "' + loan.name + '" as paid?\n\n' +
        'Monetra records it as an expense and moves the loan to its next due date. No money is moved.', 'Mark as paid')) {
        return;
    }

    const emi = Number(loan.amount) || 0;
    const outstanding = Number(loan.outstanding) || 0;
    const left = Number(loan.installmentsLeft) || 0;

    if (outstanding > 0) {
        const perYear = loan.frequency === 'WEEKLY' ? 52 : (loan.frequency === 'YEARLY' ? 1 : 12);
        const interest = outstanding * (Number(loan.rate) || 0) / 100 / perYear;

        loan.outstanding = Math.max(0, Math.round((outstanding - Math.max(0, emi - interest)) * 100) / 100);
    }

    if (left > 0) {
        loan.installmentsLeft = left - 1;
    }

    records.expenses.unshift({ id: Date.now(), name: 'EMI: ' + loan.name, amount: emi, date: today(), source: 'emi' });

    /* Fully repaid: the balance reached zero, or this was the last installment. */

    if ((outstanding > 0 && loan.outstanding === 0) || left === 1) {
        loan.status = 'CLOSED';

        showToast('EMI recorded. "' + loan.name + '" is fully repaid 🎉');

    } else {
        loan.due = nextDueDate(loan.due, loan.frequency);

        showToast('EMI recorded. Next one is due on ' + displayDate(loan.due) + '.');
    }

    saveRecords();

    renderAll();
}

/* An EMI is paid every month, so this is what it costs per day, per month and per year. */
function emiPlan(amount) {
    const perYear = (Number(amount) || 0) * 12;

    return { daily: perYear / 365, monthly: perYear / 12, yearly: perYear };
}

/* How much to save for all EMIs together, compared with the spending limit the user has set. */
function renderLoanPlan() {
    const box = $('loanPlan');

    box.replaceChildren();

    const loans = activeLoans();

    if (!loans.length) {
        return;
    }

    const monthly = overview && overview.loans ? Number(overview.loans.monthlyEmi) :
        loans.reduce((sum, loan) => sum + Number(loan.amount || 0), 0);

    const plan = emiPlan(monthly);

    box.appendChild(makeElement('div', 'alert-box', '💰 To cover your EMIs, you must save ' + money(plan.daily) +
        ' a day, ' + money(plan.monthly) + ' a month or ' + money(plan.yearly) + ' a year.'));

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

/* Loan Guardian: totals, the risk before the next income, the EMI calendar and next month's forecast. */

function renderLoanGuardian() {
    const data = overview && overview.loans ? overview.loans : null;

    const chip = $('loanRiskChip');
    const dash = $('loanDash');
    const calendar = $('loanCalendar');

    dash.replaceChildren();
    calendar.replaceChildren();

    $('loanRiskText').replaceChildren();
    $('loanForecast').replaceChildren();

    if (!data || !data.count) {
        chip.className = 'risk-chip unknown';
        chip.textContent = 'No loans';

        calendar.appendChild(makeElement('div', 'empty-state', 'Add a loan below to see your EMI dates here.'));

    } else {
        chip.className = 'risk-chip ' + (RISK_CLASS[data.status] || 'unknown');
        chip.textContent = data.statusLabel;

        const next = data.next;

        dash.append(
            statBox('Total outstanding', money(data.totalOutstanding)),
            statBox('EMI due this month', money(data.emiThisMonth)),
            statBox('Next EMI', next ? money(next.amount) + ' • ' + displayDate(next.date) : 'None soon',
                next && next.overdue ? 'var(--red)' : ''),
            statBox('Reserved for EMIs', money(data.reserved))
        );

        $('loanRiskText').appendChild(makeElement(
            'div',
            'alert-box ' + (data.status === 'HIGH' ? 'bad-box' : (data.status === 'MEDIUM' ? 'warn-box' : '')),
            (data.status === 'HIGH' ? '🚨 ' : (data.status === 'MEDIUM' ? '⚠️ ' : '🛡️ ')) + data.text
        ));

        const rows = safeArray(data.calendar);

        if (!rows.length) {
            calendar.appendChild(makeElement('div', 'empty-state', 'No EMI is due in the next 60 days.'));
        }

        rows.forEach(row => {
            const item = makeElement('div', 'record-item');

            const left = makeElement('div', 'row');

            const d = new Date(row.date + 'T00:00:00');

            const day = makeElement('span', 'cal-day' + (row.overdue ? ' overdue' : ''));

            day.appendChild(makeElement('strong', '', String(d.getDate())));
            day.appendChild(document.createTextNode(d.toLocaleDateString('en-IN', { month: 'short' })));

            left.appendChild(day);

            const text = makeElement('div');

            text.appendChild(makeElement('strong', '', row.name));

            text.appendChild(makeElement('div', 'sub' + (row.overdue ? ' warn' : ''),
                row.overdue ? 'Overdue: not marked paid'
                    : (Number(row.inDays) === 0 ? 'Due today' : 'In ' + row.inDays + ' day' + (Number(row.inDays) === 1 ? '' : 's'))));

            left.appendChild(text);

            item.appendChild(left);
            item.appendChild(makeElement('div', 'transaction-amount', money(row.amount)));

            calendar.appendChild(item);
        });
    }

    /* Next month: confirmed commitments against the income the user gave. Never an invented income. */

    const f = overview && overview.nextMonth ? overview.nextMonth : null;

    if (!f) {
        return;
    }

    const box = $('loanForecast');

    if (f.known) {
        const grid = makeElement('div', 'stat-grid');

        grid.append(
            statBox('Expected income', money(f.expectedIncome)),
            statBox('EMIs', '− ' + money(f.emis)),
            statBox('Essentials and bills', '− ' + money(Number(f.essentials) + Number(f.bills))),
            statBox('Planned savings', '− ' + money(f.savingsContributions))
        );

        box.appendChild(grid);
    }

    box.appendChild(makeElement(
        'div',
        'alert-box ' + (f.known && Number(f.projectedRemaining) < 0 ? 'bad-box' : ''),
        (f.known ? (Number(f.projectedRemaining) < 0 ? '🚨 ' : '🛡️ ') : 'ℹ️ ') + f.text
    ));
}

function updateLoans() {
    $('loanCount').textContent = activeLoans().length + ' ACTIVE';

    renderLoanPlan();

    renderRecords('loansList', records.loans, 'No loans yet.', loan => {
        const closed = loan.status === 'CLOSED';
        const overdue = !closed && loan.due < today();
        const dueToday = !closed && loan.due === today();

        const label = closed ? 'CLOSED' : (overdue ? 'OVERDUE' : (dueToday ? 'DUE TODAY' : 'Due ' + displayDate(loan.due)));

        const card = makeElement('div', 'record-item loan-item');

        const info = makeElement('div');

        info.appendChild(makeElement('strong', '', '🏦 ' + loan.name));

        info.appendChild(makeElement('div', 'sub' + (overdue || dueToday ? ' warn' : ''),
            label + ' • EMI ' + money(loan.amount) + ' ' + (LOAN_FREQUENCY[loan.frequency] || 'monthly')));

        const facts = [];

        if (Number(loan.outstanding) > 0) {
            facts.push('Outstanding ' + money(loan.outstanding) +
                (Number(loan.original) > 0 ? ' of ' + money(loan.original) : ''));
        }

        if (Number(loan.rate) > 0) {
            facts.push(Number(loan.rate) + '% a year');
        }

        if (Number(loan.installmentsLeft) > 0) {
            facts.push(loan.installmentsLeft + ' installment' + (Number(loan.installmentsLeft) === 1 ? '' : 's') + ' left');
        }

        if (loan.notes) {
            facts.push(loan.notes);
        }

        if (facts.length) {
            info.appendChild(makeElement('div', 'sub', facts.join(' • ')));
        }

        if (Number(loan.original) > 0 && Number(loan.outstanding) >= 0) {
            const progress = makeElement('div', 'progress');
            const bar = makeElement('div', 'progress-bar');

            bar.style.width = Math.min(100, Math.max(0, (1 - Number(loan.outstanding) / Number(loan.original)) * 100)) + '%';

            progress.appendChild(bar);

            info.appendChild(progress);
        }

        const actions = makeElement('div', 'row-actions');

        if (!closed) {
            const paid = makeElement('button', 'btn-gold', 'Mark EMI paid');

            paid.type = 'button';

            paid.addEventListener('click', () => markEmiPaid(loan));

            actions.appendChild(paid);
        }

        const edit = makeElement('button', '', 'Edit');

        edit.type = 'button';

        edit.addEventListener('click', () => editLoan(loan));

        actions.appendChild(edit);

        const del = makeElement('button', '', 'Delete');

        del.type = 'button';

        del.addEventListener('click', async () => {
            if (await askConfirm('Delete the loan "' + loan.name + '"?', 'Delete')) {
                if (editingLoan === loan.id) {
                    stopLoanEdit();
                }

                deleteRecord('loans', loan.id);
            }
        });

        actions.appendChild(del);

        info.appendChild(actions);

        card.appendChild(info);

        return card;
    });
}

/* ==============================
   SAVINGS AND GOALS
================================ */

const GOAL_PRIORITY = { HIGH: 'High priority', MEDIUM: 'Medium priority', LOW: 'Low priority' };

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
    const targetDate = $('goalDate').value;
    const monthly = optionalNumber('goalMonthly');
    const remindDay = Math.round(optionalNumber('goalRemind'));

    if (!name || !validAmount(target) || !Number.isFinite(saved) || saved < 0 || saved > 1000000000 ||
        !Number.isFinite(monthly) || monthly < 0 || monthly > 1000000000) {

        showToast('Enter valid goal amounts.');
        return;
    }

    if (!Number.isFinite(remindDay) || remindDay < 0 || remindDay > 28) {
        showToast('The reminder day must be from 1 to 28.');
        return;
    }

    const existing = records.goals.find(item => item.id === editingGoal);

    if (targetDate && targetDate <= today() && (!existing || existing.targetDate !== targetDate)) {
        showToast('Choose a target date in the future.');
        return;
    }

    const goal = { name, target, saved, targetDate, monthly, priority: $('goalPriority').value, remindDay };

    if (existing) {
        /* A new date or target starts a new plan from today, so "behind plan" is measured fairly. */

        if (existing.targetDate !== targetDate || Number(existing.target) !== target) {
            goal.createdDate = today();
            goal.startSaved = saved;
        }

        Object.assign(existing, goal);

        showToast('Goal updated.');

    } else {
        records.goals.unshift({ id: Date.now(), ...goal, createdDate: today(), startSaved: saved });

        showToast('Goal added.');
    }

    saveRecords();

    stopGoalEdit();

    renderAll();
});

function editGoal(goal) {
    editingGoal = goal.id;

    $('goalFormTitle').textContent = 'Edit Goal';
    $('goalFormTitle').classList.remove('hidden');
    $('goalSubmit').textContent = 'Save Changes';
    $('goalCancel').classList.remove('hidden');

    $('goalName').value = goal.name || '';
    $('goalTarget').value = goal.target || '';
    $('goalSaved').value = Number(goal.saved) || 0;
    $('goalDate').value = goal.targetDate || '';
    $('goalMonthly').value = goal.monthly || '';
    $('goalPriority').value = GOAL_PRIORITY[goal.priority] ? goal.priority : 'MEDIUM';
    $('goalRemind').value = goal.remindDay || '';

    showSubtab('goal', 'add');

    $('goalName').focus();
}

function stopGoalEdit() {
    editingGoal = null;

    $('goalForm').reset();

    $('goalSaved').value = '0';

    $('goalFormTitle').textContent = 'Create a Goal';
    $('goalFormTitle').classList.add('hidden');
    $('goalSubmit').textContent = 'Add Goal';
    $('goalCancel').classList.add('hidden');
}

$('goalCancel').addEventListener('click', stopGoalEdit);

/* The user says they put money aside for this goal. Only this changes a goal's saved amount. */

async function addGoalSavings(goal) {
    const typed = await askText('How much did you save for "' + goal.name + '"? (₹)', goal.monthly || '');

    if (typed === null) {
        return;
    }

    const amount = Math.round(Number(typed) * 100) / 100;

    if (!Number.isFinite(amount) || amount <= 0 || amount > 1000000000) {
        showToast('Enter an amount greater than ₹0.');
        return;
    }

    goal.saved = Math.round((Number(goal.saved) + amount) * 100) / 100;

    goal.contributions = safeArray(goal.contributions).slice(-49);
    goal.contributions.push({ date: today(), amount });

    records.savings.unshift({ id: Date.now(), name: 'For ' + goal.name, amount, period: 'Goal', date: today() });

    saveRecords();

    renderAll();

    showToast(goal.saved >= goal.target
        ? '🎉 You reached your goal "' + goal.name + '"!'
        : money(amount) + ' added to "' + goal.name + '".');
}

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

/* The server's figures for one goal (required saving, shortfall, advice), matched by id. */

function goalFigures(goal) {
    return safeArray(overview && overview.goals).find(g => String(g.id) === String(goal.id)) || null;
}

function updateGoals() {
    renderRecords('goalsList', records.goals, 'No goals yet.', goal => {
        const pct = Math.min(100, goal.target > 0 ? (goal.saved / goal.target) * 100 : 0);
        const figures = goalFigures(goal);
        const reached = Number(goal.saved) >= Number(goal.target);

        const card = makeElement('div', 'record-item goal-item');

        const info = makeElement('div');

        info.style.width = '100%';

        const head = makeElement('div', 'goal-head');

        head.appendChild(makeElement('strong', '', '🎯 ' + goal.name));

        if (figures && !reached) {
            const labels = { BEHIND: 'Behind plan', ON_TRACK: 'On track', DATE_PASSED: 'Date passed', NO_DATE: 'No date' };
            const levels = { BEHIND: 'HIGH', ON_TRACK: 'LOW', DATE_PASSED: 'MEDIUM', NO_DATE: 'UNKNOWN' };

            head.appendChild(riskChip(labels[figures.status] || '', levels[figures.status]));
        }

        info.appendChild(head);

        info.appendChild(makeElement('div', 'sub',
            money(goal.saved) + ' saved of ' + money(goal.target) + ' • ' + Math.round(pct) + '%' +
            (goal.targetDate ? ' • by ' + displayDate(goal.targetDate) : '') +
            ' • ' + (GOAL_PRIORITY[goal.priority] || GOAL_PRIORITY.MEDIUM)));

        const progress = makeElement('div', 'progress');

        const bar = makeElement('div', 'progress-bar');

        bar.style.width = pct + '%';

        progress.appendChild(bar);

        info.appendChild(progress);

        if (reached) {
            info.appendChild(makeElement('div', 'sub', '🎉 Goal reached!'));

        } else {
            info.appendChild(makeElement('div', 'sub', 'Still need ' + money(Math.max(0, goal.target - goal.saved))));

            if (figures && figures.requiredMonthly !== undefined) {
                info.appendChild(makeElement('div', 'sub',
                    'To reach it by ' + displayDate(figures.targetDate) + ', save about ' + money(figures.requiredMonthly) +
                    ' a month (' + money(figures.requiredWeekly) + ' a week, ' + money(figures.requiredDaily) + ' a day).'));
            }

            if (figures && Number(figures.plannedMonthly) > 0 && figures.estimatedCompletion) {
                info.appendChild(makeElement('div', 'sub',
                    'At your planned ' + money(figures.plannedMonthly) + ' a month you would reach it around ' +
                    new Date(figures.estimatedCompletion + 'T00:00:00').toLocaleDateString('en-IN', { month: 'long', year: 'numeric' }) + '.'));
            }

            if (figures && figures.advice) {
                info.appendChild(makeElement('div',
                    'alert-box ' + (figures.status === 'BEHIND' ? 'warn-box' : ''), '⚠️ ' + figures.advice));
            }
        }

        const actions = makeElement('div', 'row-actions');

        if (!reached) {
            const add = makeElement('button', 'btn-gold', 'Add savings');

            add.type = 'button';

            add.addEventListener('click', () => addGoalSavings(goal));

            actions.appendChild(add);
        }

        const edit = makeElement('button', '', 'Edit');

        edit.type = 'button';

        edit.addEventListener('click', () => editGoal(goal));

        actions.appendChild(edit);

        const del = makeElement('button', '', 'Delete');

        del.type = 'button';

        del.addEventListener('click', async () => {
            if (await askConfirm('Delete the goal "' + goal.name + '"?', 'Delete')) {
                if (editingGoal === goal.id) {
                    stopGoalEdit();
                }

                deleteRecord('goals', goal.id);
            }
        });

        actions.appendChild(del);

        info.appendChild(actions);

        card.appendChild(info);

        return card;
    });
}

/* ==============================
   FINANCIAL AWARENESS CARDS (Home) AND "MY MONEY"
================================ */

/*
 * The number inside the Daily Budget circle. Up to 999 it is shown exactly, with its per cent sign ("45%",
 * "100%", "250%"). From 1,000 up it is shortened the Indian way with a plus, meaning "at least this much":
 * "1k+", "11k+", "1L+", "2L+", "1Cr+". That always fits. The exact figure, with Indian grouping
 * ("1,11,011%"), is in the circle's tooltip and its accessible name, and the exact amount is written
 * next to the circle ("₹1,10,911.00 over").
 */

function compactIndian(n) {
    const units = [[1e7, 'Cr'], [1e5, 'L'], [1e3, 'k']];

    for (let i = 0; i < units.length; i++) {
        if (n >= units[i][0]) {
            /* Whole units, rounded down, so "+" is always true. From 99 crore up it just says 99Cr+. */
            return Math.min(99, Math.floor(n / units[i][0])) + units[i][1] + '+';
        }
    }

    return String(n);
}

function ringText(percent) {
    const n = Math.round(Number(percent) || 0);

    return n < 1000 ? n + '%' : compactIndian(n);
}

function setRing(ring, text, exact) {
    ring.firstElementChild.textContent = text;

    /* The font size follows the text's length (see .ring in the stylesheet). */
    ring.dataset.len = String(Math.min(text.length, 6));

    ring.title = exact;
    ring.setAttribute('role', 'img');
    ring.setAttribute('aria-label', exact);
}

function setChip(id, text, level) {
    const chip = $(id);

    chip.className = 'risk-chip ' + (RISK_CLASS[level] || 'unknown');
    chip.textContent = text;
}

/* Redraws everything that shows the server's Guardian figures: Home cards, Loan Guardian, goals, My money. */

function renderAwareness() {
    const o = overview || {};

    /* Card 1: daily budget. The ring shows how much of today's limit is used. */

    const daily = o.daily;
    const ring = $('awareDailyRing');

    if (daily && daily.limitSet) {
        setChip('awareDailyChip', daily.statusLabel, daily.status);

        const over = Number(daily.overBy) > 0;
        const used = Math.max(0, Number(daily.percentUsed) || 0);

        $('awareDailyMain').textContent = over ? money(daily.overBy) + ' over' : money(daily.remaining) + ' left';
        $('awareDailyMain').className = 'aware-main ' + (over ? 'bad' : 'good');

        ring.style.setProperty('--p', Math.min(100, used));
        ring.classList.toggle('over', over);

        setRing(ring, ringText(used), 'Used today: ' + used.toLocaleString('en-IN', { maximumFractionDigits: 1 }) + '% of your daily limit');

    } else {
        setChip('awareDailyChip', 'No limit set', 'UNKNOWN');

        $('awareDailyMain').textContent = 'Set a limit';
        $('awareDailyMain').className = 'aware-main';

        ring.style.setProperty('--p', 0);
        ring.classList.remove('over');

        setRing(ring, '—', 'No daily limit set');
    }

    $('awareDailySub').textContent = '';

    /* Card 2: loans: the next EMI and when it is due. */

    const loans = o.loans;
    const next = loans && loans.count ? loans.next : null;

    if (loans && loans.count) {
        setChip('awareLoansChip', loans.statusLabel, loans.status);

        $('awareLoansMain').textContent = next ? money(next.amount) + ' EMI' : 'No EMI due';

    } else {
        setChip('awareLoansChip', 'No loans', 'UNKNOWN');

        $('awareLoansMain').textContent = 'Add a loan';
    }

    $('awareLoansSub').textContent = !next ? '' : next.overdue ? 'Overdue' : 'Due ' + displayDate(next.date);
    $('awareLoansSub').className = 'aware-sub' + (next && next.overdue ? ' bad' : '');

    /* Card 3: the active savings goal. */

    const goal = o.activeGoal;

    if (goal) {
        const behind = goal.status === 'BEHIND';

        setChip('awareGoalsChip', behind ? 'Behind' : goal.percent + '%', behind ? 'HIGH' : 'LOW');

        const count = safeArray(o.goals).length;
        const by = goal.targetDate
            ? ' by ' + new Date(goal.targetDate + 'T00:00:00').toLocaleDateString('en-IN', { month: 'short' })
            : '';

        $('awareGoalsMain').textContent = count + (count === 1 ? ' goal • ' : ' goals • ') + shortMoney(goal.target) + by;
        $('awareGoalsMain').className = 'aware-main';
        $('awareGoalsSub').textContent = '';

    } else {
        const any = safeArray(o.goals).length > 0;

        setChip('awareGoalsChip', any ? 'Reached' : 'No goal', any ? 'LOW' : 'UNKNOWN');

        $('awareGoalsMain').textContent = any ? 'All reached 🎉' : 'Add a goal';
        $('awareGoalsMain').className = 'aware-main';
        $('awareGoalsSub').textContent = '';
    }

    renderSafeToSpend();
    renderLoanGuardian();
    renderLoanPlan();
    updateGoals();
    renderMoney();
}

/* My money: what the Guardian needs to judge EMIs, bills and goals. */

function renderMoney() {
    const p = records.profile || {};
    const m = overview && overview.money ? overview.money : null;

    $('moneyNow').textContent = m && m.balanceKnown ? 'Now ' + money(m.balance) : 'Not set';

    const fill = (id, value) => {
        if (document.activeElement !== $(id)) {
            $(id).value = value === undefined || value === null || value === 0 ? '' : value;
        }
    };

    /* The balance box shows the balance as it stands now, after everything recorded since it was typed. */

    fill('moneyBalance', m && m.balanceKnown ? Number(m.balance) : p.balance);
    fill('moneyIncome', p.monthlyIncome);
    fill('moneyPayday', p.payday);
    fill('moneyEssentials', p.essentials);
    fill('moneyBuffer', p.buffer);
}

$('moneyForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const balanceText = $('moneyBalance').value.trim();

    const values = {
        monthlyIncome: optionalNumber('moneyIncome'),
        payday: Math.round(optionalNumber('moneyPayday')),
        essentials: optionalNumber('moneyEssentials'),
        buffer: optionalNumber('moneyBuffer')
    };

    const balance = balanceText === '' ? null : Number(balanceText);

    if (Object.values(values).some(n => !Number.isFinite(n) || n < 0 || n > 1000000000) ||
        (balance !== null && (!Number.isFinite(balance) || balance < 0 || balance > 1000000000))) {

        showToast('Enter amounts of ₹0 or more.');
        return;
    }

    if (values.payday > 31) {
        showToast('The income day must be a day of the month from 1 to 31.');
        return;
    }

    const shown = overview && overview.money && overview.money.balanceKnown ? Number(overview.money.balance) : null;

    Object.assign(records.profile, values);

    if (balance === null) {
        delete records.profile.balance;
        delete records.profile.balanceAt;

    } else if (balance !== shown || records.profile.balance === undefined) {
        /* A new balance starts counting from now: later payments and expenses move it. */

        records.profile.balance = balance;
        records.profile.balanceAt = Date.now();
    }

    saveRecords();

    showToast('Saved. The Guardian now uses these figures.');
});

/* ==============================
   HOME TILES AND REDRAW
================================ */

const FORECAST_STATUS = {
    ok: ['On track', 'good'],
    watch: ['Close to limit', 'warn'],
    over: ['Over limit pace', 'bad'],
    unknown: ['No monthly limit', '']
};

function updateTiles() {
    const status = forecast && FORECAST_STATUS[forecast.status];

    $('tileForecast').textContent = status ? status[0] : '';
    $('tileForecast').className = 'tile-status ' + (status ? status[1] : '');

    renderSpark();

    const flagged = payments.filter(p => p.direction === 'SENT' && p.riskFlags).length;

    $('tileFraud').textContent = flagged ? flagged + ' flagged' : 'Secure';
    $('tileFraud').className = 'tile-status ' + (flagged ? 'warn' : 'good');
    $('tileFraudIcon').className = 'row-icon ' + (flagged ? 'warn' : 'ok');
}

/* Spending Forecast tile: what was spent each day over the last 14 days, as one line. */

function renderSpark() {
    const totals = [];

    for (let i = 13; i >= 0; i--) {
        const d = new Date();

        d.setDate(d.getDate() - i);

        const key = dateKey(d);

        totals.push(spentBetween(key, key));
    }

    const max = Math.max(1, ...totals);
    const points = totals.map((t, i) => (i * 300 / 13).toFixed(1) + ',' + (62 - t / max * 50).toFixed(1));
    const ns = 'http://www.w3.org/2000/svg';

    const area = document.createElementNS(ns, 'polygon');

    area.setAttribute('points', '0,70 ' + points.join(' ') + ' 300,70');
    area.setAttribute('class', 'spark-area');

    const line = document.createElementNS(ns, 'polyline');

    line.setAttribute('points', points.join(' '));
    line.setAttribute('class', 'spark-line');

    const dots = points.filter((_, i) => i % 2 === 1).map(p => {
        const [x, y] = p.split(',');
        const dot = document.createElementNS(ns, 'circle');

        dot.setAttribute('cx', x);
        dot.setAttribute('cy', y);
        dot.setAttribute('r', '3.2');
        dot.setAttribute('class', 'spark-dot');

        return dot;
    });

    $('tileSpark').replaceChildren(area, line, ...dots);
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
    renderNotifySettings();
    renderPlanTracking();

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
            ? 'The AI is off on this server. Everything else still works.'
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

function renderPaypalSystem() {
    if (document.activeElement !== $('paypalEmailInput')) {
        $('paypalEmailInput').value = me.paypalEmail || '';
    }

    $('paypalEmailHint').textContent = me.paypalEmail
        ? 'Paid out to ' + me.paypalEmail + '.'
        : 'Add your PayPal Sandbox email to get paid.';

    const wrap = $('paymentTable');

    wrap.replaceChildren();

    const rows = payments.slice(0, 10);

    if (!rows.length) {
        wrap.appendChild(makeElement('div', 'empty-state', 'No payments yet.'));
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
        'All caught up. 🎉', row => {

        const item = makeElement('div', 'record-item');

        const left = makeElement('div');

        left.appendChild(makeElement('strong', '', row.name + ' • ' + money(row.amount)));

        left.appendChild(makeElement('div', 'sub',
            displayDate(row.dueDate) + ' • ' + row.category + ' • ' + String(row.frequency).toLowerCase()));

        const actions = makeElement('div', 'row-actions');

        const paid = makeElement('button', '', 'Mark paid');

        paid.type = 'button';

        paid.addEventListener('click', () => markHomePaid(row));

        actions.appendChild(paid);

        const pay = makeElement('button', 'btn-gold', 'Pay with Monetra');

        pay.type = 'button';

        pay.addEventListener('click', () => payHomeExpense(row));

        actions.appendChild(pay);

        left.appendChild(actions);

        item.appendChild(left);

        item.appendChild(makeElement('span', 'badge ' + row.status.toLowerCase(), HOME_STATUS[row.status] || row.status));

        return item;
    });

    renderHomeReport();

    renderRecords('homeAll', safeArray(homeData.expenses), 'No home expenses yet.', e => {
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
    if (!await askConfirm('Delete "' + expense.name + '" and its history?', 'Delete')) {
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

/* Pays a planned household bill in HE. It is marked paid when PayPal confirms it, or when cash is recorded. */

function payHomeExpense(row) {
    navigateTo('heView');

    showSubtab('he', 'pay');

    showHeType(row.frequency === 'DAILY' ? 'DAILY' : row.frequency === 'YEARLY' ? 'YEARLY' : 'MONTHLY');

    heBill = { expenseId: row.expenseId, dueDate: row.dueDate, name: row.name };

    $('heAmount').value = Number(row.amount);
    $('heNote').value = row.name;
    $('hePhone').value = row.recipientPhone || '';

    setHeMethod('MOBILE');

    if (row.recipientPhone) {
        lookupHeRecipient();
    }
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

        showSubtab('ghome', 'todo');

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
   HE: HOME EXPENSES
   Kept apart from Pay: three payment options (Daily, Monthly, Yearly), four payment
   methods, their own reminders and their own transaction history.

   Scan & Pay and Mobile Number are paid through PayPal and are only ever shown as
   successful when PayPal confirms them. Cash is recorded, not sent.
================================ */

const HE_TYPES = { DAILY: 'Daily', MONTHLY: 'Monthly', YEARLY: 'Yearly' };

const HE_METHODS = {
    SCAN: 'Scan & Pay',
    MYQR: 'My Payment QR',
    MOBILE: 'Mobile number',
    BANK: 'Bank transfer',      /* only for bank transfers recorded before the option was removed */
    CASH: 'Cash'
};

/* What the server's verified status is called on screen, and its colour. */

const HE_STATUS = {
    PAID: ['Successful', 'completed'],
    COMPLETED: ['Successful', 'completed'],
    CREATED: ['Pending', 'pending'],
    AWAITING_APPROVAL: ['Pending', 'pending'],
    FAILED: ['Failed', 'failed'],
    CANCELLED: ['Cancelled', 'cancelled']
};

/* Used until the server says what each method really does with its payment provider. */

const HE_DEFAULT_HOW = {
    SCAN: 'Scan a QR code. You approve on PayPal.',
    MYQR: 'Your QR code, for others to pay you.',
    MOBILE: 'Pay a mobile number. You approve on PayPal.',
    CASH: 'Record cash you paid. No money moves.'
};

let heMethod = 'MOBILE';
let heMethodInfo = {};          /* id -> { available, how } from the server */
let hePayee = null;             /* who a Scan or Mobile payment goes to, once looked up */
let heScannedName = '';
let heScanned = false;
let heLookupRun = 0;
let heLookupTimer = null;
let heBusy = false;             /* one payment at a time, however often Pay Now is pressed */
let heOpenRow = '';             /* the history row whose details are open */
let heDrafts = {};              /* what is typed into each option's form: Daily, Monthly and Yearly keep their own */
let heBill = null;              /* a planned household bill this payment settles: { expenseId, dueDate, name } */

const HE_DRAFT_FIELDS = ['heAmount', 'heNote', 'hePhone', 'heCashTo'];

function isHePaypal(method) {
    return method === 'SCAN' || method === 'MOBILE';
}

async function loadHeMethods() {
    try {
        const data = await apiJson('/api/he/methods', {}, 10000);

        heMethodInfo = {};

        safeArray(data.methods).forEach(m => {
            heMethodInfo[m.id] = m;
        });

    } catch (error) {
        /* The built-in descriptions are shown instead. */
    }

    renderHeForm();
}

function keepHeDraft() {
    const draftNow = { method: heMethod };

    HE_DRAFT_FIELDS.forEach(id => {
        draftNow[id] = $(id).value;
    });

    heDrafts[heType] = draftNow;
}

/* Shows the fields the chosen method needs, and nothing else. */

function renderHeForm() {
    document.querySelectorAll('#heTabs button').forEach(button => {
        button.classList.toggle('active', button.dataset.he === heType);
    });

    $('heFormTitle').textContent = heBill ? heBill.name : HE_TYPES[heType] + ' HE Payment';
    $('heFormTitle').parentElement.classList.toggle('hidden', !heBill);

    document.querySelectorAll('#heMethods button').forEach(button => {
        const info = heMethodInfo[button.dataset.method];

        button.classList.toggle('active', button.dataset.method === heMethod);
        button.disabled = !!info && info.available === false;
    });

    const info = heMethodInfo[heMethod];

    $('heMethodHint').textContent = info && info.how ? info.how : HE_DEFAULT_HOW[heMethod];

    const paypal = isHePaypal(heMethod);

    $('heScanBox').classList.toggle('hidden', heMethod !== 'SCAN');
    $('heQrBox').classList.toggle('hidden', heMethod !== 'MYQR');
    $('hePayeeBox').classList.toggle('hidden', !(heMethod === 'MOBILE' || (heMethod === 'SCAN' && heScanned)));
    $('heCashBox').classList.toggle('hidden', heMethod !== 'CASH');
    $('heAmountRow').classList.toggle('hidden', heMethod === 'MYQR');

    const button = $('hePay');

    button.classList.toggle('hidden', heMethod === 'MYQR');
    button.textContent = paypal ? 'Pay Now' : 'Record Payment';
    button.disabled = heBusy || (!!info && info.available === false);

    if (heMethod === 'SCAN' && !$('heScanMessage').textContent) {
        $('heScanMessage').textContent = 'Point the camera at a QR code.';
    }

    if (heMethod === 'MYQR') {
        loadHeQr();
    }
}

function setHeMethod(method) {
    if (!document.querySelector('#heMethods button[data-method="' + method + '"]')) {
        return;
    }

    if (method !== 'SCAN') {
        stopScan();
    }

    heMethod = method;

    $('heMessage').textContent = '';
    $('heContactList').classList.add('hidden');

    renderHeForm();
}

document.querySelectorAll('#heMethods button').forEach(button => {
    button.addEventListener('click', () => setHeMethod(button.dataset.method));
});

function showHeType(type) {
    keepHeDraft();

    heBill = null;

    stopScan();

    heType = HE_TYPES[type] ? type : 'DAILY';

    const saved = heDrafts[heType] || {};

    HE_DRAFT_FIELDS.forEach(id => {
        $(id).value = saved[id] || '';
    });

    heMethod = saved.method || 'MOBILE';
    hePayee = null;
    heScanned = false;
    heScannedName = '';

    $('heMessage').textContent = '';
    $('heScanMessage').textContent = '';
    $('heRecipientCard').classList.add('hidden');

    renderHeForm();

    if (heMethod === 'MOBILE' && phoneKeyFrom($('hePhone').value)) {
        lookupHeRecipient();
    }
}

document.querySelectorAll('#heTabs button').forEach(button => {
    button.addEventListener('click', () => showHeType(button.dataset.he));
});

/* My Payment QR: the user's own Monetra QR code, drawn by the server. */

async function loadHeQr() {
    $('heMyPhone').textContent = me.phone ? '+91 ' + me.phone : '';

    try {
        const response = await fetch('/api/qr/me', { cache: 'no-store' });

        if (!response.ok) {
            throw new Error('QR not available');
        }

        $('heMyQr').src = 'data:image/svg+xml;charset=utf-8,' + encodeURIComponent(await response.text());

    } catch (error) {
        $('heMyQr').removeAttribute('src');
        $('heMyPhone').textContent = 'Could not draw your QR code right now.';
    }
}

/* ---------- who is being paid (Scan & Pay, Mobile Number) ---------- */

/* What is wrong with a typed mobile number, or '' when it is fine. A country code is allowed. */

function mobileProblem(text) {
    const typed = String(text || '').trim();
    const digits = typed.replace(/\D/g, '');

    if (!digits) {
        return 'Enter the mobile number you are paying, for example +91 98765 43210.';
    }

    if (/[a-z]/i.test(typed)) {
        return 'Pick a contact from the list, or enter a mobile number.';
    }

    let local = digits;

    if (digits.length === 12 && digits.startsWith('91')) {
        local = digits.slice(2);

    } else if (digits.length === 11 && digits.startsWith('0')) {
        local = digits.slice(1);

    } else if (typed.startsWith('+') && !digits.startsWith('91')) {
        return 'Only Indian mobile numbers (+91) can be paid from Monetra.';
    }

    if (local.length !== 10) {
        return 'A mobile number has 10 digits, with or without +91 in front.';
    }

    if (!/^[6-9]/.test(local)) {
        return 'That does not look like an Indian mobile number: they start with 6, 7, 8 or 9.';
    }

    return '';
}

async function lookupHeRecipient() {
    const key = phoneKeyFrom($('hePhone').value);

    hePayee = null;

    if (!key || mobileProblem($('hePhone').value)) {
        $('heRecipientCard').classList.add('hidden');
        return null;
    }

    const run = ++heLookupRun;

    renderRecipientCard(null, 'Looking up ' + key.slice(0, 2) + 'xxxxxx' + key.slice(8) + '...', 'wait', 'heRecipientCard');

    try {
        const info = await apiJson('/api/payments/recipient?phone=' + key, {}, 10000);

        if (run !== heLookupRun) {
            return null;
        }

        hePayee = { key, ...info };

        if (info.self) {
            renderRecipientCard(null, 'This is your own number. Enter someone else\'s.', 'warn', 'heRecipientCard');

        } else if (info.onMonetra) {
            renderRecipientCard(info, 'On Monetra ✓ • ' + info.maskedPhone, 'found', 'heRecipientCard');

        } else {
            /* The same as on the Pay screen: a number that is not on Monetra can still be paid through PayPal. */

            renderRecipientCard({ name: heScannedName || info.name },
                'Not on Monetra yet • ' + info.maskedPhone + ' • you can still pay through PayPal', 'new', 'heRecipientCard');
        }

        return hePayee;

    } catch (error) {
        if (run === heLookupRun) {
            renderRecipientCard(null, error.message, 'warn', 'heRecipientCard');
        }

        return null;
    }
}

$('hePhone').addEventListener('input', function() {
    heScannedName = '';

    suggestContacts(CONTACT_BOXES.he);

    clearTimeout(heLookupTimer);

    if (contactQuery(CONTACT_BOXES.he).complete) {
        heLookupTimer = setTimeout(lookupHeRecipient, 350);

    } else {
        hePayee = null;

        $('heRecipientCard').classList.add('hidden');
    }
});

function pickHeContact(contact) {
    $('heContactList').classList.add('hidden');

    $('hePhone').value = contact.phone;

    heScannedName = '';

    lookupHeRecipient();

    $('heAmount').focus();
}

/* A QR code was read: fill in who it belongs to, then the user enters the amount. */

function applyHeScan(payeeInfo) {
    if (!payeeInfo.phone) {
        $('heScanMessage').textContent =
            'That QR code has no mobile number in it. Try another code, or use Mobile Number.';
        return false;
    }

    heScannedName = payeeInfo.name || '';
    heScanned = true;

    $('hePhone').value = payeeInfo.phone;

    $('heScanMessage').textContent = 'QR code read ✓ Check the name below, then enter the amount.';

    renderHeForm();

    lookupHeRecipient();

    $('heAmount').focus();

    return true;
}

/* ---------- reminders ---------- */

/* Reminder times: picked as hour, minutes and AM or PM; kept as 24-hour "HH:MM", as the server reads them. */

function twoDigits(n) {
    return String(n).padStart(2, '0');
}

function timeFromPick(box) {
    const hour = Number(box.querySelector('.t-hour').value) % 12;
    const pm = box.querySelector('.t-ampm').value === 'PM';

    return twoDigits(hour + (pm ? 12 : 0)) + ':' + box.querySelector('.t-min').value;
}

function setTimePick(id, value) {
    const hidden = $(id);
    const box = hidden.parentElement;
    const parts = String(value || '09:00').split(':').map(Number);
    const hour = Number.isInteger(parts[0]) && parts[0] >= 0 && parts[0] < 24 ? parts[0] : 9;
    const minute = Number.isInteger(parts[1]) && parts[1] >= 0 && parts[1] < 60 ? parts[1] : 0;
    box.querySelector('.t-hour').value = String(hour % 12 || 12);
    box.querySelector('.t-min').value = twoDigits(minute);
    box.querySelector('.t-ampm').value = hour < 12 ? 'AM' : 'PM';

    hidden.value = twoDigits(hour) + ':' + twoDigits(minute);
}

function buildTimePick(id) {
    const hidden = $(id);
    const box = hidden.parentElement;

    const add = (cls, label, values) => {
        const select = document.createElement('select');

        select.className = cls;
        select.setAttribute('aria-label', label);

        values.forEach(v => select.appendChild(new Option(v, v)));

        select.addEventListener('change', () => {
            hidden.value = timeFromPick(box);
        });

        box.appendChild(select);

        return select;
    };

    add('t-hour', 'Hour', ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12']).id = id + 'H';
    add('t-min', 'Minutes', Array.from({ length: 60 }, (_, i) => twoDigits(i)));
    add('t-ampm', 'AM or PM', ['AM', 'PM']);

    setTimePick(id, hidden.value);
}

['heRemDailyTime', 'heRemMonthlyTime', 'heRemYearlyTime'].forEach(buildTimePick);

function renderHeReminders() {
    const r = records.he.reminders || {};

    const daily = r.daily || {};
    const monthly = r.monthly || {};
    const yearly = r.yearly || {};

    $('heRemDailyOn').checked = !!daily.on;
    setTimePick('heRemDailyTime', daily.time || '09:00');

    $('heRemMonthlyOn').checked = !!monthly.on;
    $('heRemMonthlyDay').value = monthly.day || 1;
    setTimePick('heRemMonthlyTime', monthly.time || '09:00');

    $('heRemYearlyOn').checked = !!yearly.on;
    $('heRemYearlyDate').value = yearly.date || '';
    setTimePick('heRemYearlyTime', yearly.time || '09:00');
}

function renderHe() {
    renderHeForm();
    renderHeReminders();
    renderHeHistory();
}

/* ---------- history ---------- */

function heDetailLine(label, value) {
    const line = makeElement('div', 'detail-line');

    line.appendChild(makeElement('span', '', label));
    line.appendChild(makeElement('strong', '', value));

    return line;
}

function renderHeHistory() {
    renderRecords('heHistory', heHistory, 'No payments yet.', p => {
        const status = HE_STATUS[p.status] || [STATUS_LABELS[p.status] || p.status, String(p.status).toLowerCase()];
        const open = heOpenRow === p.id;

        const row = makeElement('div', 'record-item he-row');

        const main = makeElement('button', 'he-row-main');

        main.type = 'button';
        main.setAttribute('aria-expanded', open ? 'true' : 'false');

        const left = makeElement('span');

        left.appendChild(makeElement('strong', '', money(p.amountInr) + (p.recipient ? ' to ' + p.recipient : '')));

        left.appendChild(makeElement('small', '',
            displayDateTime(p.createdAt) + ' • ' + (HE_METHODS[p.method] || p.method) + ' • ' + (HE_TYPES[p.type] || p.type) + ' HE'));

        left.appendChild(makeElement('small', '', 'ID ' + p.transactionId));

        main.appendChild(left);
        main.appendChild(makeElement('span', 'badge ' + status[1], status[0]));

        main.addEventListener('click', () => {
            heOpenRow = open ? '' : p.id;

            renderHeHistory();
        });

        row.appendChild(main);

        if (open) {
            const details = makeElement('div', 'he-details');

            details.appendChild(heDetailLine('Status', status[0] + (p.source === 'PAYPAL'
                ? (p.status === 'COMPLETED' ? ' (confirmed by PayPal)' : '')
                : ' (recorded by you, no money moved by Monetra)')));

            details.appendChild(heDetailLine('Amount', money(p.amountInr) + (p.amountUsd ? ' (' + usd(p.amountUsd) + ' on PayPal)' : '')));
            details.appendChild(heDetailLine('Paid to', p.recipient || 'Not given'));
            details.appendChild(heDetailLine('Payment method', (HE_METHODS[p.method] || p.method) + (p.detail ? ' • ' + p.detail : '')));
            details.appendChild(heDetailLine('Home expense', (HE_TYPES[p.type] || p.type) + (p.description ? ' • ' + p.description : '')));
            details.appendChild(heDetailLine('Date', displayDateTime(p.createdAt)));
            details.appendChild(heDetailLine('Transaction ID', p.transactionId));

            if (p.orderId) {
                details.appendChild(heDetailLine('PayPal order', p.orderId));
            }

            if (p.reference) {
                details.appendChild(heDetailLine('Bank reference', p.reference));
            }

            if (p.message) {
                details.appendChild(heDetailLine('Note', p.message));
            }

            if (p.source === 'PAYPAL' && (p.status === 'AWAITING_APPROVAL' || (p.status === 'FAILED' && p.orderId))) {
                const retry = makeElement('button', 'small-button', 'Check with PayPal');

                retry.type = 'button';

                retry.addEventListener('click', () => recoverPayment(p.paymentId, retry));

                details.appendChild(retry);
            }

            row.appendChild(details);
        }

        return row;
    });
}

async function loadHe() {
    if (!me.email) {
        return;
    }

    try {
        heHistory = safeArray(await apiJson('/api/he/history', {}, 15000));

    } catch (error) {
        return;
    }

    syncHeIntoRecords();

    if (currentView === 'heView') {
        renderHeHistory();
    }
}

/* A paid home expense is spending too: it becomes an expense exactly once, so limits and balance see it. */

function syncHeIntoRecords() {
    let changed = false;

    heHistory.forEach(p => {
        if (p.status !== 'PAID' && p.status !== 'COMPLETED') {
            return;
        }

        const ref = 'he:' + p.id;

        if (records.expenses.some(item => item.ref === ref)) {
            return;
        }

        records.expenses.unshift({
            id: new Date(p.createdAt).getTime(),
            name: 'Home: ' + (p.description || HE_TYPES[p.type] + ' home expense'),
            amount: Number(p.amountInr),
            date: dateKey(new Date(p.createdAt)),
            source: 'he',
            ref
        });

        changed = true;
    });

    if (changed) {
        saveRecords();

        renderAll();
    }
}

$('heRefresh').addEventListener('click', loadHe);

/* ---------- Pay Now ---------- */

/* Cash: nothing is sent. It is recorded once the user confirms they really paid. */

async function recordHePayment(amount, description, label) {
    const body = { type: heType, amountInr: amount, description, method: heMethod, confirmed: true, ...heBillLink() };

    body.recipient = $('heCashTo').value.trim();

    const question = 'Did you pay ' + money(amount) + ' in cash' + (body.recipient ? ' to ' + body.recipient : '') + '?\n\n' +
        'Monetra will mark this ' + label.toLowerCase() + ' as paid. No money is moved.';

    if (!await askConfirm(question, 'Yes, record it', 'No')) {
        return 'Nothing was recorded.';
    }

    body.requestId = 'he-' + Date.now() + '-' + Math.random().toString(36).slice(2, 10);

    await postJson('/api/he/pay', body, 15000);

    ['heAmount', 'heNote', 'heCashTo'].forEach(id => {
        $(id).value = '';
    });

    const settled = !!heBill;

    heBill = null;

    showPaymentResult('✅ ' + label + ' of ' + money(amount) + ' recorded as paid (' + HE_METHODS[heMethod].toLowerCase() +
        '). Monetra moved no money.', 'good-box', 'heResult');

    await loadHe();

    if (settled) {
        loadHome();
        loadOverview();
    }

    return '';
}

/* The planned bill this payment settles, for the server. */

function heBillLink() {
    return heBill ? { homeExpenseId: heBill.expenseId, homeDueDate: heBill.dueDate } : {};
}

/* Scan & Pay and Mobile Number: the Guardian and Fraud Guard check it, then PayPal's own page takes over. */

async function startHePaypalPayment(amount, description, label) {
    const problem = mobileProblem($('hePhone').value);

    if (heMethod === 'SCAN' && !heScanned) {
        throw new Error('Scan a QR code first, or choose its picture.');
    }

    if (problem) {
        throw new Error(problem);
    }

    if (amount > MAX_PAYMENT_INR) {
        throw new Error('A payment through PayPal can be at most ' + money(MAX_PAYMENT_INR) + '.');
    }

    const key = phoneKeyFrom($('hePhone').value);

    const to = hePayee && hePayee.key === key ? hePayee : await lookupHeRecipient();

    if (!to) {
        throw new Error('Monetra could not look up that number just now. Try again.');
    }

    if (to.self) {
        throw new Error('This is your own number. Enter someone else\'s.');
    }

    const payload = {
        method: heMethod === 'SCAN' ? 'QR' : 'PHONE',
        recipientPhone: key,
        scannedName: heMethod === 'SCAN' ? heScannedName : '',
        amountInr: amount,
        description: description || label,
        heType,
        heMethod,
        ...heBillLink()
    };

    $('heMessage').textContent = 'Checking with the Guardian...';

    const [check, risk] = await Promise.all([
        postJson('/api/guardian/check', payload, 15000),
        postJson('/api/payments/risk', payload, 15000)
    ]);

    /* Each thing the Guardian found is its own red box, like on the Pay review. */

    const warnings = guardianAlertCards(check, risk);

    const question = 'Pay ' + money(amount) + ' to ' + risk.recipientName + ' through PayPal' + (warnings ? ' anyway?' : '?');

    /*
     * The same four buttons as the Pay review. "Continue" starts the payment exactly as before; the others
     * close the box and nothing is started: Go Back, Adjust Amount (back to the amount) or Review Budget.
     */

    const answer = await askConfirm(question, warnings ? 'Continue Anyway' : 'Continue to PayPal', 'Go Back', warnings, true);

    if (answer === 'adjust') {
        $('heAmount').focus();
        $('heAmount').select();

        return 'Payment not started. Change the amount, then press Pay Now.';
    }

    if (answer === 'budget') {
        navigateTo('spendingView');

        return 'Payment not started.';
    }

    if (answer !== true) {
        return 'Payment not started.';
    }

    $('heMessage').textContent = 'Creating your PayPal Sandbox order...';

    const order = await postJson('/api/payments/create-order', { ...payload, confirmRisk: !!risk.flagged }, 30000);

    $('heMessage').textContent = 'Opening PayPal...';

    goToPaypal(order.approvalUrl);

    return 'Opening PayPal...';
}

$('heForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    /* Pressing Pay Now again while a payment is being made does nothing. */

    if (heBusy || heMethod === 'MYQR') {
        return;
    }

    const message = $('heMessage');

    const amount = Math.round(Number($('heAmount').value) * 100) / 100;
    const description = $('heNote').value.trim();
    const label = HE_TYPES[heType] + ' home expense';

    message.textContent = '';

    $('heResult').classList.add('hidden');

    if (!Number.isFinite(amount) || amount <= 0) {
        message.textContent = 'Enter the amount you are paying.';
        return;
    }

    heBusy = true;

    $('hePay').disabled = true;

    try {
        message.textContent = isHePaypal(heMethod)
            ? await startHePaypalPayment(amount, description, label)
            : await recordHePayment(amount, description, label);

    } catch (error) {
        message.textContent = error.message;

    } finally {
        heBusy = false;

        renderHeForm();
    }
});

$('heReminderForm').addEventListener('submit', function(event) {
    event.preventDefault();

    const day = Math.round(Number($('heRemMonthlyDay').value));

    if ($('heRemMonthlyOn').checked && !(day >= 1 && day <= 31)) {
        showToast('Choose a day of the month from 1 to 31 for the monthly reminder.');
        return;
    }

    if ($('heRemYearlyOn').checked && !$('heRemYearlyDate').value) {
        showToast('Choose the date for the yearly reminder.');
        return;
    }

    records.he.reminders = {
        daily: { on: $('heRemDailyOn').checked, time: $('heRemDailyTime').value || '09:00' },
        monthly: { on: $('heRemMonthlyOn').checked, day: day >= 1 && day <= 31 ? day : 1, time: $('heRemMonthlyTime').value || '09:00' },
        yearly: { on: $('heRemYearlyOn').checked, date: $('heRemYearlyDate').value, time: $('heRemYearlyTime').value || '09:00' }
    };

    saveRecords();

    showToast('HE reminders saved.');
});

/* ==============================
   GUARDIAN NOTIFICATION CHOICES
================================ */

function renderNotifySettings() {
    const choices = records.profile.notify || {};

    $('notifyDaily').checked = choices.daily !== false;
    $('notifyLoans').checked = choices.loans !== false;
    $('notifySavings').checked = choices.savings !== false;
    $('notifyScoreDaily').checked = choices.scoreDaily !== false;
    $('notifyBadges').checked = choices.badges !== false;
    $('notifyDevice').checked = deviceNotifications();
}

[['notifyScoreDaily', 'scoreDaily', 'Daily Guardian updates are'], ['notifyBadges', 'badges', 'Badge notifications are']].forEach(([id, key, label]) => {
    $(id).addEventListener('change', function() {
        records.profile.notify[key] = this.checked;

        saveRecords();

        showToast(label + (this.checked ? ' on.' : ' off.'));
    });
});

/*
 * Notifications on this device: the browser shows them while Monetra is open, if the user allows it.
 * There is no push service, so nothing arrives while Monetra is closed. The choice is kept on this
 * device only.
 */

const DEVICE_NOTIFY_KEY = 'monetraDeviceNotifications';

function deviceNotifications() {
    try {
        return 'Notification' in window && Notification.permission === 'granted' && localStorage.getItem(DEVICE_NOTIFY_KEY) === 'on';

    } catch (error) {
        return false;
    }
}

$('notifyDevice').addEventListener('change', async function() {
    const box = this;

    if (!box.checked) {
        try {
            localStorage.removeItem(DEVICE_NOTIFY_KEY);
        } catch (error) {
            /* Storage unavailable. */
        }

        showToast('Device notifications are off.');
        return;
    }

    if (!('Notification' in window)) {
        box.checked = false;
        showToast('This browser cannot show notifications.');
        return;
    }

    const permission = Notification.permission === 'granted' ? 'granted' : await Notification.requestPermission();

    if (permission !== 'granted') {
        box.checked = false;
        showToast('Notifications are blocked for Monetra in this browser.');
        return;
    }

    try {
        localStorage.setItem(DEVICE_NOTIFY_KEY, 'on');
    } catch (error) {
        /* Storage unavailable: it stays on until the page is reloaded. */
    }

    showToast('Device notifications are on while Monetra is open.');
});

function showOnDevice(n) {
    if (!deviceNotifications()) {
        return;
    }

    try {
        const shown = new Notification(NOTIFICATION_TITLES[n.type] || 'Monetra', { body: n.message, tag: 'monetra-' + n.id });

        shown.onclick = () => {
            window.focus();

            if (SCORE_NOTIFICATIONS.includes(n.type)) {
                openScoreFor(n.type);
            }

            shown.close();
        };

    } catch (error) {
        /* Some browsers only allow notifications from a service worker; the in-app list still has it. */
    }
}

['Daily', 'Loans', 'Savings'].forEach(name => {
    $('notify' + name).addEventListener('change', function() {
        records.profile.notify[name.toLowerCase()] = this.checked;

        saveRecords();

        showToast(name + ' notifications are ' + (this.checked ? 'on.' : 'off.'));
    });
});

/* ==============================
   GUARDIAN SCORE
   Worked out by the server from the user's own records. The page only shows it:
   it never sends a score or a badge back.
================================ */

let guardianScore = null;
let scoreSort = 'high';
let scoreZoneSent = false;

const BADGE_STATUS = {
    CURRENT: ['Current', 'current'],
    EARNED: ['Previously Earned', 'earned'],
    NOT_EARNED: ['Not Yet Earned', 'not-earned']
};

/* The first load after signing in also tells the server the user's own time zone, for the daily update. */

async function loadScore() {
    if (!me.email) {
        return;
    }

    let zone = '';

    try {
        zone = Intl.DateTimeFormat().resolvedOptions().timeZone || '';
    } catch (error) {
        zone = '';
    }

    try {
        if (!scoreZoneSent && zone) {
            try {
                guardianScore = await postJson('/api/guardian/score/refresh', { timezone: zone }, 15000);
            } catch (error) {
                guardianScore = await apiJson('/api/guardian/score', {}, 15000);
            }

            scoreZoneSent = true;

        } else {
            guardianScore = await apiJson('/api/guardian/score', {}, 15000);
        }

    } catch (error) {
        return;     /* the last score stays on screen */
    }

    renderScoreButton();

    if (currentView === 'scoreView') {
        renderScore();
    }
}

function renderScoreButton() {
    const s = guardianScore;
    const known = !!(s && s.available);

    /* The button shows only "B"; the score itself is on the Guardian Score screen. */
    $('scoreButton').classList.toggle('unknown', !known);
}

function openScore(tab, badgeTab) {
    navigateTo('scoreView');
    showSubtab('score', typeof tab === 'string' ? tab : 'overall');

    if (badgeTab) {
        showBadgeTab(badgeTab);
    }
}

/* The Badges tab: Daily, Weekly or Levels. */

function showBadgeTab(tab) {
    [['daily', 'badgeTabDaily', 'badgePaneDaily'],
     ['weekly', 'badgeTabWeekly', 'badgePaneWeekly'],
     ['levels', 'badgeTabLevels', 'badgePaneLevels']].forEach(([name, button, pane]) => {
        $(button).classList.toggle('active', name === tab);
        $(pane).classList.toggle('hidden', name !== tab);
    });
}

function sortScore(order) {
    scoreSort = order === 'low' ? 'low' : 'high';

    renderScoreParts();
}

function renderScore() {
    const s = guardianScore;
    const known = !!(s && s.available);

    /* The shield: the score big, and out of 100 under it. */

    $('scoreValue').textContent = known ? s.score : '—';
    $('scoreOutOf').textContent = (known ? s.score : '—') + '/100';

    /* Only when there is no score: what to add to get one. */

    $('scoreConfidenceText').textContent = s && !known ? s.confidenceText || '' : '';

    renderScoreParts();
    renderScoreBadges();
    renderDayBadges();
    renderWeekBadges();
    renderScoreTips();
}

function renderScoreParts() {
    const s = guardianScore;

    $('scoreSortHigh').classList.toggle('active', scoreSort === 'high');
    $('scoreSortLow').classList.toggle('active', scoreSort === 'low');

    const parts = safeArray(s && s.categories);
    const order = safeArray(s && s.order && s.order[scoreSort]);

    /* The server worked out the order; the page only follows it. */

    const sorted = order.length
        ? order.map(id => parts.find(p => p.id === id)).filter(Boolean)
        : parts;

    renderRecords('scoreParts', sorted, s && !s.available ? s.confidenceText : 'Loading...', p => {
        const item = makeElement('div', 'record-item score-part');

        const head = makeElement('div', 'score-part-head');

        head.appendChild(makeElement('strong', '', p.name));
        head.appendChild(makeElement('span', 'score-points', p.earned + '/' + p.max));

        item.appendChild(head);

        const bar = makeElement('div', 'score-bar small');
        const fill = document.createElement('i');

        fill.style.setProperty('--p', p.percent);
        bar.appendChild(fill);
        item.appendChild(bar);

        /* One line: what to do, or else why. */

        item.appendChild(p.advice
            ? makeElement('div', 'score-advice', p.advice)
            : makeElement('div', 'sub', p.explanation));

        return item;
    });
}

function renderScoreBadges() {
    const s = guardianScore;

    $('scoreBadgeEmpty').classList.toggle('hidden', !!(s && s.anyBadge));

    renderRecords('scoreBadges', safeArray(s && s.badges), '', b => {
        const status = BADGE_STATUS[b.status] || BADGE_STATUS.NOT_EARNED;
        const item = makeElement('div', 'record-item badge-row ' + status[1]);

        const left = makeElement('div');

        left.appendChild(makeElement('strong', '', '🏅 ' + b.badge));
        left.appendChild(makeElement('div', 'sub', b.earnedAt
            ? displayDateTime(b.earnedAt) + ' • score ' + b.scoreWhenEarned + '/100'
            : b.min + '–' + b.max + ' points'));

        item.appendChild(left);
        item.appendChild(makeElement('span', 'badge-status', status[0]));

        return item;
    });
}

/* "Mon 13 Oct" from "2026-10-13". */

function shortDay(iso) {
    return new Date(iso + 'T00:00:00').toLocaleDateString('en-IN', { weekday: 'short', day: 'numeric', month: 'short' });
}

const DAY_STATUS = {
    EARNED: ['earned', 'Good Day badge'],
    MISSED: ['missed', 'Over the limit'],
    TODAY_ON_TRACK: ['today', 'On track'],
    TODAY_OVER: ['today over', 'Over the limit'],
    NONE: ['none', 'Not checked']
};

/* Daily: today so far, the last 7 days as a strip, and the totals. */

function renderDayBadges() {
    const p = guardianScore && guardianScore.periods;

    const limitSet = !!(p && p.limitSet);
    const onTrack = !!(p && p.todayOnTrack);

    $('todayBadgeText').textContent = limitSet
        ? money(p.spentToday) + ' of ' + money(p.limit)
        : 'Set a daily limit in Spending';

    $('todayBadgeChip').textContent = limitSet ? (onTrack ? 'On track' : 'Over limit') : '';
    $('todayBadgeChip').className = 'risk-chip ' + (onTrack ? 'low' : 'high') + (limitSet ? '' : ' hidden');
    $('todayBadgeIcon').className = 'row-icon' + (limitSet && !onTrack ? ' warn' : '');

    const strip = $('dayStrip');

    strip.replaceChildren(...safeArray(p && p.days).map(d => {
        const status = DAY_STATUS[d.status] || DAY_STATUS.NONE;
        const isToday = d.status && d.status.startsWith('TODAY');
        const date = new Date(d.date + 'T00:00:00');

        const cell = makeElement('div', 'day-cell ' + status[0]);

        cell.title = shortDay(d.date) + ': ' + status[1] + (d.spent != null ? ', ' + money(d.spent) + ' spent' : '');

        cell.appendChild(makeElement('span', 'day-name', isToday ? 'Today' : date.toLocaleDateString('en-IN', { weekday: 'short' })));

        const dot = makeElement('span', 'day-dot');

        if (d.status === 'EARNED' || isToday) {
            dot.innerHTML = '<svg class="i"><use href="#i-shield"/></svg>';

        } else {
            dot.textContent = d.status === 'MISSED' ? '✕' : '–';
        }

        cell.appendChild(dot);
        cell.appendChild(makeElement('span', 'day-date', String(date.getDate())));
        /* What was spent that day, short: ₹180, ₹1.2k. */
        const spent = Number(d.spent);

        cell.appendChild(makeElement('span', 'day-spent',
            d.spent == null ? '' : spent < 1000 ? '₹' + Math.round(spent) : shortMoney(spent)));

        return cell;
    }));

    $('goodDaysTotal').textContent = p ? String(p.goodDaysTotal || 0) : '0';
    $('goodWeeksTotal').textContent = p ? String(p.goodWeeksTotal || 0) : '0';
}

/* Weekly: this week so far, and each week already decided. */

function renderWeekBadges() {
    const p = guardianScore && guardianScore.periods;

    const good = p ? Number(p.weekGoodDays) || 0 : 0;
    const needed = p ? Number(p.weekNeeded) || 5 : 5;
    const left = p ? Number(p.weekDaysLeft) || 0 : 0;

    $('weekNowCount').textContent = good + '/' + needed;
    $('weekNowBar').style.setProperty('--p', Math.min(100, Math.round(good / needed * 100)));

    $('weekNowText').textContent = !(p && p.limitSet)
        ? 'Set a daily limit in Spending'
        : good >= needed
            ? 'Badge on Monday'
            : good + left >= needed
                ? left + (left === 1 ? ' day left' : ' days left')
                : 'Not possible this week';

    renderRecords('weekList', safeArray(p && p.weeks), 'Your first week is checked on Monday.', w => {
        const item = makeElement('div', 'record-item badge-row ' + (w.earned ? 'earned' : 'not-earned'));
        const left = makeElement('div');

        left.appendChild(makeElement('strong', '', (w.earned ? '🏆 ' : '') + shortDay(w.start) + ' – ' + shortDay(w.end)));
        left.appendChild(makeElement('div', 'sub', w.goodDays + ' of 7 Good Days'));

        item.appendChild(left);
        item.appendChild(makeElement('span', 'badge-status', w.earned ? 'Good Week' : 'Missed'));

        return item;
    });
}

function renderScoreTips() {
    const s = guardianScore;

    renderRecords('scoreTips', safeArray(s && s.tips),
        s && !s.available ? s.confidenceText : 'Nothing to improve right now. 🎉', t => {
            const item = makeElement('div', 'record-item score-tip');

            item.appendChild(makeElement('span', '', t.text));

            if (t.gain) {
                item.appendChild(makeElement('span', 'gain-chip', '≈ +' + t.gain + ' points'));
            }

            return item;
        });
}


/* ==============================
   SETTINGS (the gear, top right)
   Kept for the signed-in account in its records, and on this device so the sign-in page
   opens the same way before anyone signs in.
================================ */

const PREFS_KEY = 'monetraPrefs';

const NOTIFY_MODES = ['SOUND_ONLY', 'VIBRATION_ONLY', 'BOTH'];

function defaultPrefs() {
    return { notifyMode: 'SOUND_ONLY' };
}

/* Only known values are kept, so a broken saved value can never break the page. */

function cleanPrefs(raw) {
    const p = { ...defaultPrefs() };
    const r = raw && typeof raw === 'object' ? raw : {};

    if (NOTIFY_MODES.includes(r.notifyMode)) {
        p.notifyMode = r.notifyMode;
    }

    return p;
}

function localPrefs() {
    try {
        return cleanPrefs(JSON.parse(localStorage.getItem(PREFS_KEY) || '{}'));
    } catch (error) {
        return defaultPrefs();
    }
}

let prefs = localPrefs();

function keepPrefsHere() {
    try {
        localStorage.setItem(PREFS_KEY, JSON.stringify(prefs));
    } catch (error) {
        /* Storage unavailable: the account still keeps them. */
    }
}

/* Saves one or more choices, applies them at once, and keeps them for the account. */

function savePrefs(changes) {
    prefs = cleanPrefs({ ...prefs, ...changes });

    keepPrefsHere();

    if (me.email) {
        records.profile.prefs = { ...prefs };
        saveRecords();
    }

    applyPrefs();
}

/* Signed in: the account's choices are used; an account without any keeps this device's. */

function adoptAccountPrefs() {
    const saved = records.profile && records.profile.prefs;

    if (saved && typeof saved === 'object') {
        prefs = cleanPrefs(saved);
        keepPrefsHere();

    } else {
        records.profile.prefs = { ...prefs };
        saveRecords();
    }

    applyPrefs();
}

function applyPrefs() {
    renderSettings();
}

function openSettings() {
    navigateTo('settingsView');
    renderSettings();
}

function canVibrate() {
    return typeof navigator !== 'undefined' && typeof navigator.vibrate === 'function';
}

function renderSettings() {
    if (!$('settingsView')) {
        return;
    }

    document.querySelectorAll('#settingsView [data-mode]').forEach(card => {
        const chosen = card.dataset.mode === prefs.notifyMode;

        card.classList.toggle('chosen', chosen);
        card.setAttribute('aria-checked', chosen ? 'true' : 'false');
    });

    /* Said only when it matters: vibration was chosen and this device has none. */
    $('vibrateNote').classList.toggle('hidden', prefs.notifyMode === 'SOUND_ONLY' || canVibrate());
}

document.querySelectorAll('#settingsView [data-mode]').forEach(card => {
    card.addEventListener('click', () => {
        savePrefs({ notifyMode: card.dataset.mode });
        notifyAlert();
    });
});

$('testNotify').addEventListener('click', () => {
    const played = notifyAlert();

    showToast(played
        ? '🔔 This is how Monetra tells you about something new.'
        : 'This device cannot play the sound or vibrate right now.');
});

/* ==============================
   NOTIFICATION SOUND AND VIBRATION
   The Monetra chime is made by the browser itself, so there is no sound file to download.
   Browsers only allow sound after the user has touched the page once.
================================ */

let chimeContext = null;

function audioContext() {
    const Audio = window.AudioContext || window.webkitAudioContext;

    if (!Audio) {
        return null;
    }

    if (!chimeContext) {
        try {
            chimeContext = new Audio();
        } catch (error) {
            return null;
        }
    }

    return chimeContext;
}

/* The first touch or key press unlocks sound for the rest of the visit. */

['pointerdown', 'keydown'].forEach(type => {
    window.addEventListener(type, () => {
        const ctx = audioContext();

        if (ctx && ctx.state === 'suspended') {
            ctx.resume().catch(() => {});
        }
    }, { passive: true });
});

/* Two soft rising notes. False when the browser does not allow sound right now. */

function playChime() {
    const ctx = audioContext();

    if (!ctx) {
        return false;
    }

    if (ctx.state === 'suspended') {
        ctx.resume().catch(() => {});

        if (ctx.state === 'suspended') {
            return false;
        }
    }

    try {
        const now = ctx.currentTime;

        [[880, 0], [1318.5, 0.13]].forEach(([frequency, start]) => {
            const tone = ctx.createOscillator();
            const volume = ctx.createGain();

            tone.type = 'sine';
            tone.frequency.value = frequency;

            volume.gain.setValueAtTime(0.0001, now + start);
            volume.gain.exponentialRampToValueAtTime(0.22, now + start + 0.02);
            volume.gain.exponentialRampToValueAtTime(0.0001, now + start + 0.5);

            tone.connect(volume);
            volume.connect(ctx.destination);

            tone.start(now + start);
            tone.stop(now + start + 0.55);
        });

        return true;

    } catch (error) {
        return false;
    }
}

function buzz() {
    if (!canVibrate()) {
        return false;
    }

    try {
        return navigator.vibrate([120, 80, 120]) !== false;
    } catch (error) {
        return false;
    }
}

/* Sound, vibration or both, as chosen in Settings. True when anything played. */

function notifyAlert() {
    const mode = prefs.notifyMode;

    let done = false;

    if (mode !== 'VIBRATION_ONLY') {
        done = playChime() || done;
    }

    if (mode !== 'SOUND_ONLY') {
        done = buzz() || done;
    }

    return done;
}

applyPrefs();

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

    /* Back from Setu's consent screens. */
    const bankReturn = params.get('bank') === 'return';

    /* The server session tells us who is logged in, even after a refresh. */

    let profile = null;

    try {
        profile = await apiJson('/api/auth/me', {}, 8000);

    } catch (error) {
        profile = null;
    }

    if (returning || bankReturn) {
        /* Remove the PayPal or Setu parameters so a refresh cannot repeat the request. */

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

    if (bankReturn) {
        await handleBankReturn();
    }
}

/* ==============================
   SAFE TO SPEND TODAY (Home)
   Worked out by the server from the user's own balance, after everything
   that has to be paid before the next income.
================================ */

function openMyMoney() {
    navigateTo('spendingView');

    showSubtab('spending', 'money');
}

/* ₹20k, ₹1.5L: short amounts for the Home rows. */

function shortMoney(value) {
    const n = Number(value) || 0;

    if (n >= 100000) {
        return '₹' + (Math.round(n / 10000) / 10) + 'L';
    }

    if (n >= 1000) {
        return '₹' + (Math.round(n / 100) / 10) + 'k';
    }

    return money(n);
}

function renderSafeToSpend() {
    const s = overview && overview.safeToSpend;
    const amount = $('safeAmount');

    if (!s || !s.known) {
        amount.textContent = '—';
        amount.className = 'safe-amount unknown';

        $('safeAvail').classList.add('hidden');
        $('safeText').textContent = s && s.text ? s.text : 'Tap to add your balance and income day.';
        return;
    }

    amount.textContent = money(s.amount);
    amount.className = 'safe-amount ' + (RISK_CLASS[s.status] || 'low');


    /* The share of today's safe money that is still unspent. */

    const perDay = Number(s.perDay);
    const left = perDay > 0 ? Math.max(0, Math.min(100, Math.round(Number(s.amount) / perDay * 100))) : 0;

    $('safeMeter').style.setProperty('--p', left);
    $('safePercent').textContent = left + '%';
    $('safeAvail').classList.toggle('hidden', !(perDay > 0));

    /* Only a warning is written out; otherwise the three figures say it all. */

    $('safeText').textContent = s.status === 'HIGH' ? s.text : '';
}

/* ==============================
   SMART BUY PLANNER (Savings)
   "When can I buy it?" The server follows the user's money day by day
   and finds the first safe day. Nothing is bought or moved.
================================ */

let planRun = 0;

$('planForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    const message = $('planMessage');

    const request = {
        item: $('planItem').value.trim(),
        priceInr: $('planPrice').value,
        wantedDate: $('planDate').value,
        savingMonthly: $('planSaving').value
    };

    if (!request.item || !(Number(request.priceInr) > 0)) {
        message.textContent = 'Enter what you want to buy and its price.';
        return;
    }

    const run = ++planRun;

    message.textContent = 'Working out your plan...';

    $('planSubmit').disabled = true;

    try {
        const plan = await postJson('/api/guardian/buy-plan', request, 15000);

        if (run !== planRun) {
            return;
        }

        message.textContent = '';

        renderPlan({ ...plan, priority: $('planPriority').value, request }, run);

    } catch (error) {
        message.textContent = error.message;

    } finally {
        $('planSubmit').disabled = false;
    }
});

function showPlanForm() {
    planRun++;

    $('planResult').classList.add('hidden');
    $('planForm').classList.remove('hidden');

    renderPlanTracking();
}

/* The plan's own sentences that are not already shown as a number above them. */

function planNotes(plan) {
    return safeArray(plan.lines).filter(line =>
        !line.startsWith('You want to buy') &&
        !line.startsWith('Buying it today is NOT safe') &&
        !line.startsWith('To buy it on') &&
        !line.startsWith('You can buy it today') &&
        !(line.startsWith('The earliest safe day') && plan.safeDate === plan.estimatedDate));
}

function renderPlan(plan, run) {
    const box = $('planResult');

    box.replaceChildren();

    box.appendChild(makeElement('h2', '', (plan.item || 'Your purchase') + ' • ' + money(plan.price)));

    const buttons = makeElement('div', 'button-row');

    const change = makeElement('button', '', 'Change details');

    change.type = 'button';
    change.addEventListener('click', showPlanForm);

    buttons.appendChild(change);

    if (!plan.ready) {
        box.appendChild(makeElement('div', 'alert-box', 'ℹ️ ' + safeArray(plan.lines).join(' ')));

        const open = makeElement('button', 'btn-gold', 'Open My money');

        open.type = 'button';
        open.addEventListener('click', openMyMoney);

        buttons.appendChild(open);
        box.appendChild(buttons);

        $('planForm').classList.add('hidden');
        box.classList.remove('hidden');
        return;
    }

    const status = plan.buyToday
        ? ['✅ Safe to buy today', 'good']
        : plan.estimatedDate
            ? ['⛔ Not safe to buy today', 'bad']
            : ['⏳ No safe day in the next 12 months', 'bad'];

    box.appendChild(makeElement('div', 'plan-status ' + status[1], status[0]));

    const stats = makeElement('div', 'stat-grid');

    if (plan.buyToday) {
        stats.appendChild(statBox('Free to use now', money(plan.freeNow)));
        stats.appendChild(statBox('Lowest balance after', money(plan.lowestAfter)));

    } else if (plan.estimatedDate) {
        stats.appendChild(statBox('Buy it on', displayDate(plan.estimatedDate), 'var(--green)'));
        stats.appendChild(statBox('Wait', plan.daysToWait + (plan.daysToWait === 1 ? ' day' : ' days')));
        stats.appendChild(statBox('Save a day', money(plan.perDay)));

        /* A month's figure only for a wait of a month or more, a week's for a week or more. */
        stats.appendChild(plan.daysToWait >= 30 ? statBox('Save a month', money(plan.perMonth))
            : plan.daysToWait >= 7 ? statBox('Save a week', money(plan.perWeek))
                : statBox('In all', money(plan.needed)));

    } else {
        stats.appendChild(statBox('Free to use now', money(plan.freeNow)));
        stats.appendChild(statBox('Free each month', plan.freeMonthly === null || plan.freeMonthly === undefined
            ? 'Not known' : money(plan.freeMonthly)));
    }

    box.appendChild(stats);

    /* Why not today, in a red box, with what to do. The AI's advice replaces that line when it comes. */

    let adviceLine;

    if (plan.warning) {
        const why = plan.warning.replace(/^Buying it today is NOT safe:\s*/, '');

        const red = alertBox(0, 'WHY NOT TODAY', why.charAt(0).toUpperCase() + why.slice(1), plan.recommendation);

        box.appendChild(red.item);

        adviceLine = red.item.querySelector('.alert-action');

    } else {
        adviceLine = makeElement('div', 'alert-box good-box', '✅ ' + plan.recommendation);

        box.appendChild(adviceLine);
    }

    const notes = planNotes(plan);

    if (notes.length) {
        const list = makeElement('ul', 'plan-notes');

        notes.forEach(text => list.appendChild(makeElement('li', '', text)));

        box.appendChild(list);
    }

    if (!plan.buyToday && plan.estimatedDate) {
        const track = makeElement('button', 'btn-gold', 'Track this plan');

        track.type = 'button';
        track.addEventListener('click', () => trackPlan(plan, track));

        buttons.appendChild(track);
    }

    box.appendChild(buttons);

    $('planForm').classList.add('hidden');
    box.classList.remove('hidden');

    renderPlanTracking();

    explainPlan(plan, run, adviceLine);
}

async function explainPlan(plan, run, adviceLine) {
    const waiting = makeElement('div', 'footer-note', '🛡️ The Guardian AI is writing its advice...');

    adviceLine.after(waiting);

    try {
        const result = await postJson('/api/guardian/buy-plan/explain', plan.request, 90000);

        if (run !== planRun) {
            return;
        }

        adviceLine.textContent = (plan.warning ? '👉 ' : '') + '🛡️ Guardian AI: ' + String(result.advice || '').slice(0, 500);

    } catch (error) {
        /* No AI advice: the plan above is complete without it. */
    }

    waiting.remove();
}

/* "Track this plan": it becomes a savings goal, so its progress shows here and under My goals. */

function trackPlan(plan, button) {
    const name = (plan.item || 'Purchase').slice(0, 60);

    /* The goal is what the plan says to put aside (part of the price is already free money), by the plan's date. */

    const target = Number(plan.needed) || Number(plan.price);

    if (records.goals.some(g => g.plan && g.name === name && g.targetDate === plan.estimatedDate)) {
        showToast('You are already saving for "' + name + '".');
        return;
    }

    records.goals.unshift({
        id: Date.now(),
        name,
        target,
        saved: 0,
        targetDate: plan.estimatedDate,
        monthly: plan.daysToWait >= 30 ? Number(plan.perMonth) || 0 : target,
        priority: plan.priority || 'MEDIUM',
        remindDay: 0,
        createdDate: today(),
        startSaved: 0,
        plan: true
    });

    saveRecords();

    renderAll();

    button.disabled = true;
    button.textContent = 'Tracking ✓';

    showToast('"' + name + '" is now a goal: put aside ' + money(target) + ' by ' + displayDate(plan.estimatedDate) + '.');
}

function renderPlanTracking() {
    const plans = records.goals.filter(g => g.plan);

    /* Shown under the form. While a result is open it would only push the page into scrolling. */
    $('planTracking').classList.toggle('hidden', !plans.length || !$('planResult').classList.contains('hidden'));

    renderRecords('planList', plans, '', goal => {
        const pct = Math.min(100, goal.target > 0 ? (Number(goal.saved) / Number(goal.target)) * 100 : 0);

        const row = makeElement('div', 'record-item');

        const info = makeElement('div', 'record-info');

        info.appendChild(makeElement('strong', '', goal.name));

        info.appendChild(makeElement('div', 'sub', money(goal.saved) + ' of ' + money(goal.target) + ' • ' +
            Math.round(pct) + '%' + (goal.targetDate ? ' • buy on ' + displayDate(goal.targetDate) : '')));

        const progress = makeElement('div', 'progress');
        const bar = makeElement('div', 'progress-bar');

        bar.style.width = pct + '%';

        progress.appendChild(bar);
        info.appendChild(progress);

        row.appendChild(info);

        if (pct < 100) {
            const add = makeElement('button', 'small-button', 'Add savings');

            add.type = 'button';
            add.addEventListener('click', () => addGoalSavings(goal));

            row.appendChild(add);

        } else {
            row.appendChild(makeElement('span', 'badge completed', 'Ready to buy'));
        }

        return row;
    });
}

/* ==============================
   SCAM SHIELD (Monetra Guardian)
   The pasted message is checked by the server's rules. It is not saved,
   and the AI only ever sees what the rules found, never the message.
================================ */

const SCAM_TITLES = {
    PIN: 'ASKS FOR YOUR PIN',
    OTP: 'ASKS FOR AN OTP',
    REMOTE: 'SCREEN-SHARING APP',
    ARREST: 'ARREST THREAT',
    PASSWORD: 'ASKS FOR CARD OR LOGIN DETAILS',
    RECEIVE: 'FAKE "RECEIVE MONEY"',
    QR: 'QR CODE TRICK',
    APK: 'APP FILE TO INSTALL',
    ELECTRICITY: 'ELECTRICITY CUT THREAT',
    KYC: 'ACCOUNT BLOCK THREAT',
    PRIZE: 'PRIZE OR LOTTERY',
    FEE: 'PAY A FEE FIRST',
    AUTHORITY: 'CLAIMS TO BE AN OFFICIAL',
    JOB: 'EASY MONEY OFFER',
    FAMILY: 'NEW NUMBER, NEEDS MONEY',
    SHORT_LINK: 'HIDDEN LINK',
    URGENT: 'PRESSURE TO ACT FAST',
    LINK: 'LINK IN THE MESSAGE',
    CALL: 'CALL THIS NUMBER'
};

let scamRun = 0;

$('scamForm').addEventListener('submit', async function(event) {
    event.preventDefault();

    const text = $('scamText').value.trim();
    const message = $('scamMessage');

    if (!text) {
        message.textContent = 'Paste the message you want to check.';
        return;
    }

    const run = ++scamRun;

    message.textContent = 'Checking...';

    try {
        const result = await postJson('/api/guardian/scam/check', { message: text }, 15000);

        if (run !== scamRun) {
            return;
        }

        message.textContent = '';

        renderScam(result, text, run);

    } catch (error) {
        message.textContent = error.message;
    }
});

/* The message with the words that gave it away marked. Built from text only: nothing in it is ever run as HTML. */

function markedMessage(text, phrases) {
    const box = makeElement('div', 'scam-text');
    const lower = text.toLowerCase();

    const marks = [];

    safeArray(phrases).forEach(phrase => {
        const needle = String(phrase).toLowerCase();

        if (!needle) {
            return;
        }

        for (let i = lower.indexOf(needle); i >= 0; i = lower.indexOf(needle, i + needle.length)) {
            marks.push([i, i + needle.length]);
        }
    });

    marks.sort((a, b) => a[0] - b[0]);

    let at = 0;

    marks.forEach(([start, end]) => {
        if (start < at) {
            return;
        }

        box.appendChild(document.createTextNode(text.slice(at, start)));
        box.appendChild(makeElement('mark', '', text.slice(start, end)));

        at = end;
    });

    box.appendChild(document.createTextNode(text.slice(at)));

    return box;
}

function renderScam(result, text, run) {
    const box = $('scamResult');

    box.replaceChildren();

    const head = makeElement('div', 'card-head');

    /* The level is shown once, in the coloured line under the title. */
    head.appendChild(makeElement('h2', '', 'Scam Shield result'));

    box.appendChild(head);

    const risky = result.level !== 'LOW';

    box.appendChild(makeElement('div', 'plan-status ' + (result.level === 'HIGH' ? 'bad' : risky ? 'warn' : 'good'),
        (risky ? '⚠️ ' : '✅ ') + 'Risk level: ' + result.level));

    /* One numbered red box per reason, then what to do. The AI's explanation goes in that last box. */

    let adviceText;

    if (risky) {
        let n = 0;

        safeArray(result.reasons).forEach(reason => {
            box.appendChild(alertBox(n++, SCAM_TITLES[reason.rule] || 'WARNING SIGN', reason.text, '').item);
        });

        const todo = alertBox(n, 'WHAT TO DO', result.recommendation, '');

        box.appendChild(todo.item);

        adviceText = todo.body;

    } else {
        adviceText = makeElement('div', 'alert-box good-box', result.reason + ' ' + result.recommendation);

        box.appendChild(adviceText);
    }

    if (safeArray(result.phrases).length) {
        box.appendChild(makeElement('label', '', 'Words that gave it away'));
        box.appendChild(markedMessage(text, result.phrases));
    }

    box.appendChild(scamReportBox(result, text));

    const again = makeElement('button', '', 'Check another message');

    again.type = 'button';

    again.addEventListener('click', () => {
        scamRun++;

        $('scamText').value = '';

        box.classList.add('hidden');
        $('scamForm').classList.remove('hidden');

        $('scamText').focus();
    });

    const row = makeElement('div', 'button-row');

    row.appendChild(again);
    box.appendChild(row);

    $('scamForm').classList.add('hidden');
    box.classList.remove('hidden');

    explainScam(text, run, adviceText);
}

async function explainScam(text, run, adviceText) {
    const waiting = makeElement('div', 'footer-note', '🛡️ The Guardian AI is writing its explanation...');

    adviceText.after(waiting);

    try {
        const result = await postJson('/api/guardian/scam/explain', { message: text }, 90000);

        if (run === scamRun) {
            adviceText.textContent = '🛡️ Guardian AI: ' + String(result.advice || '').slice(0, 500);
        }

    } catch (error) {
        /* The rules' result above is complete without it. */
    }

    waiting.remove();
}

/* "Is this result wrong?" The message goes with the report only if the user ticks the box. */

function scamReportBox(result, text) {
    const wrap = makeElement('div', 'scam-report');

    wrap.appendChild(makeElement('span', 'sub', 'Is this result wrong?'));

    const share = makeElement('label', 'check-row');
    const tick = document.createElement('input');

    tick.type = 'checkbox';

    share.appendChild(tick);
    share.appendChild(document.createTextNode('Send the message with my report'));

    const send = says => async () => {
        try {
            const saved = await postJson('/api/guardian/scam/report', {
                level: result.level,
                userSays: says,
                rules: safeArray(result.reasons).map(r => r.rule),
                shareMessage: tick.checked,
                message: tick.checked ? text : ''
            }, 10000);

            wrap.replaceChildren(makeElement('span', 'sub', '✅ Thank you, your report was saved' +
                (saved.messageKept ? ' with the message.' : '. The message was not kept.')));

        } catch (error) {
            showToast(error.message);
        }
    };

    const buttons = makeElement('div', 'scam-report-buttons');

    [['It is a scam', 'SCAM'], ['It is safe', 'SAFE']].forEach(([label, says]) => {
        const b = makeElement('button', 'small-button', label);

        b.type = 'button';
        b.addEventListener('click', send(says));

        buttons.appendChild(b);
    });

    wrap.appendChild(buttons);
    wrap.appendChild(share);

    return wrap;
}

/* ==============================
   CHECK BALANCE (Setu Account Aggregator)
   The server talks to Setu. The page only shows what the bank returned:
   it never shows a balance the bank did not send.
================================ */

let bankAccounts = [];          /* the linked accounts, from the server */
let bankChosen = null;          /* the account shown on the Check Balance screen */
let bankBusy = false;
let bankTimer = null;
let bankRefreshMinutes = 60;

function openCheckBalance() {
    navigateTo('bankView');
}

/* "HDFC Bank • XXXX1234 (SAVINGS)" */

function bankAccountLabel(a) {
    return (a.bank || 'Your bank') + (a.maskedAccNumber ? ' • ' + a.maskedAccNumber : '') +
        (a.accountType ? ' (' + a.accountType + ')' : '');
}

function setBankMessage(text, warn) {
    const box = $('bankMessage');

    box.textContent = text || '';
    box.classList.toggle('warn', !!warn);
}

function renderBankAccounts() {
    const select = $('bankAccountSelect');
    const many = bankAccounts.length > 1;

    select.replaceChildren();

    bankAccounts.forEach(a => {
        const option = document.createElement('option');

        option.value = a.id;
        option.textContent = bankAccountLabel(a) + (a.primary ? ' — main' : '');

        select.appendChild(option);
    });

    if (!bankChosen || !bankAccounts.some(a => a.id === bankChosen)) {
        const main = bankAccounts.find(a => a.primary) || bankAccounts[0];

        bankChosen = main ? main.id : null;
    }

    select.value = bankChosen === null ? '' : String(bankChosen);

    select.classList.toggle('hidden', !many);
    $('bankAccountName').classList.toggle('hidden', many);

    const chosen = bankAccounts.find(a => a.id === bankChosen);

    $('bankAccountName').textContent = chosen ? bankAccountLabel(chosen) : 'No bank account linked';

    $('bankRefresh').classList.toggle('hidden', !bankAccounts.length);
    $('bankLinkFirst').classList.toggle('hidden', !!bankAccounts.length);
    $('bankLink').classList.toggle('hidden', !bankAccounts.length);
    $('bankPrimary').classList.toggle('hidden', !chosen || chosen.primary || !many);
    $('bankRemove').classList.toggle('hidden', !chosen);
    $('bankRefresh').disabled = bankBusy || !chosen;
}

/* Shows one account's balance exactly as the server returned it. */

function renderBankBalance(result) {
    bankAccounts = safeArray(result.accounts);

    if (result.account) {
        bankChosen = result.account.id;
    }

    renderBankAccounts();

    const a = result.account;

    $('bankBalance').textContent = a && a.balance !== null && a.balance !== undefined
        ? money(a.balance)
        : '—';

    $('bankLastChecked').textContent = a && a.lastCheckedAt ? displayDateTime(a.lastCheckedAt) : 'Not yet';

    $('bankBalance').classList.toggle('stale', !!result.stale);

    if (result.state === 'OK') {
        setBankMessage('');

    } else if (result.stale) {
        /* The last real balance stays on screen, marked as possibly out of date. */
        setBankMessage('This may be out of date. ' + (result.message || ''), true);

    } else {
        setBankMessage(result.message || '', result.state !== 'NO_ACCOUNT');
    }
}

/* Asks the server for the chosen account's balance. A new one is fetched from the bank only when allowed. */

async function checkBankBalance(manual) {
    if (bankBusy || !me.email) {
        return;
    }

    bankBusy = true;

    const shown = currentView === 'bankView';
    const before = (bankAccounts.find(a => a.id === bankChosen) || {}).lastCheckedAt;

    if (shown) {
        $('bankRefresh').disabled = true;
        $('bankRefresh').textContent = 'Checking...';
        setBankMessage('Checking your balance...');
    }

    try {
        const result = await postJson('/api/bank/balance', { accountId: bankChosen }, 60000);

        if (typeof result.autoRefreshMinutes === 'number') {
            bankRefreshMinutes = Math.max(5, result.autoRefreshMinutes);
        }

        if (currentView === 'bankView') {
            renderBankBalance(result);

            /* Pressed Refresh, but the bank allows one update an hour: say when the next one can be. */

            const after = result.account && result.account.lastCheckedAt;

            if (manual && result.state === 'OK' && after && after === before && result.nextUpdateAt) {
                setBankMessage('The balance can be updated once an hour. Next update after ' +
                    new Date(result.nextUpdateAt).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' }) + '.');
            }

        } else {
            bankAccounts = safeArray(result.accounts);
        }

    } catch (error) {
        if (currentView === 'bankView') {
            setBankMessage(error.message, true);
        }

    } finally {
        bankBusy = false;

        if (currentView === 'bankView') {
            $('bankRefresh').textContent = 'Refresh Balance';
            renderBankAccounts();
        }
    }
}

/* Opening the screen: what is linked, then the balance of the main account. */

async function loadBank() {
    setBankMessage('');

    try {
        const status = await apiJson('/api/bank', {}, 15000);

        bankAccounts = safeArray(status.accounts);

        if (typeof status.autoRefreshMinutes === 'number') {
            bankRefreshMinutes = Math.max(5, status.autoRefreshMinutes);
        }

        renderBankAccounts();

        const chosen = bankAccounts.find(a => a.id === bankChosen);

        $('bankBalance').textContent = chosen && chosen.balance !== null && chosen.balance !== undefined ? money(chosen.balance) : '—';
        $('bankLastChecked').textContent = chosen && chosen.lastCheckedAt ? displayDateTime(chosen.lastCheckedAt) : 'Not yet';

        if (!status.configured) {
            setBankMessage('Check Balance is not set up on this server yet. It needs Setu Account Aggregator keys: ' +
                safeArray(status.missing).join(', ') + '.', true);
            return;
        }

        if (!bankAccounts.length) {
            setBankMessage(status.pendingConsents ? 'Consent not approved yet. Finish on Setu, or link again.' : '');
            return;
        }

        checkBankBalance(false);

    } catch (error) {
        setBankMessage(error.message, true);
    }
}

/* Linking: Setu's own screens ask the user to pick their bank, verify with an OTP and approve. */

async function startBankLink() {
    const button = this;

    button.disabled = true;
    setBankMessage('Opening Setu...');

    try {
        const result = await postJson('/api/bank/link', {}, 30000);

        goToSetu(result.url);

    } catch (error) {
        setBankMessage(error.message, true);
        button.disabled = false;
    }
}

$('bankLink').addEventListener('click', startBankLink);
$('bankLinkFirst').addEventListener('click', startBankLink);

/* Sends the browser to Setu's consent screens, and nowhere else. */

function goToSetu(address) {
    const url = new URL(address);

    if (url.protocol !== 'https:' || !/(^|\.)setu\.co$/.test(url.hostname)) {
        throw new Error('The server returned an unexpected linking address.');
    }

    window.location.assign(url.toString());
}

/* Back from Setu's screens: ask what became of the consent, then show the balance. */

async function handleBankReturn() {
    navigateTo('bankView');

    setBankMessage('Checking your consent with Setu...');

    try {
        const result = await postJson('/api/bank/consents/check', {}, 30000);

        bankAccounts = safeArray(result.accounts);

        renderBankAccounts();

        setBankMessage(safeArray(result.messages).join(' '));

        if (bankAccounts.length) {
            checkBankBalance(false);
        }

    } catch (error) {
        setBankMessage(error.message, true);
    }
}

$('bankAccountSelect').addEventListener('change', function() {
    bankChosen = Number(this.value);

    const chosen = bankAccounts.find(a => a.id === bankChosen);

    $('bankBalance').textContent = chosen && chosen.balance !== null && chosen.balance !== undefined ? money(chosen.balance) : '—';
    $('bankLastChecked').textContent = chosen && chosen.lastCheckedAt ? displayDateTime(chosen.lastCheckedAt) : 'Not yet';

    renderBankAccounts();

    checkBankBalance(false);
});

$('bankRefresh').addEventListener('click', () => checkBankBalance(true));

$('bankBack').addEventListener('click', goBack);

$('bankPrimary').addEventListener('click', async function() {
    try {
        const result = await postJson('/api/bank/accounts/' + bankChosen + '/primary', {}, 15000);

        bankAccounts = safeArray(result.accounts);

        renderBankAccounts();

        showToast('This is now your main account.');

    } catch (error) {
        setBankMessage(error.message, true);
    }
});

$('bankRemove').addEventListener('click', async function() {
    const chosen = bankAccounts.find(a => a.id === bankChosen);

    if (!chosen || !await askConfirm('Remove ' + bankAccountLabel(chosen) + '?\n\n' +
        'Monetra will revoke its consent at Setu and will no longer be able to read this account.', 'Remove', 'Go Back')) {
        return;
    }

    try {
        const result = await apiJson('/api/bank/accounts/' + chosen.id, { method: 'DELETE' }, 30000);

        bankAccounts = safeArray(result.accounts);
        bankChosen = null;

        renderBankAccounts();

        $('bankBalance').textContent = '—';
        $('bankLastChecked').textContent = 'Not yet';

        setBankMessage('The account was removed and its consent revoked.');

        if (bankAccounts.length) {
            checkBankBalance(false);
        }

    } catch (error) {
        setBankMessage(error.message, true);
    }
});

/*
 * Automatic updates while Monetra is open: one check when the dashboard opens, then one every
 * refresh interval (an hour, Setu's limit for a consent). The server asks the bank only when a new
 * balance is due, so these checks never use up more than the consent allows.
 */

async function startBankUpdates() {
    clearTimeout(bankTimer);

    try {
        const status = await apiJson('/api/bank', {}, 15000);

        bankAccounts = safeArray(status.accounts);

        if (typeof status.autoRefreshMinutes === 'number') {
            bankRefreshMinutes = Math.max(5, status.autoRefreshMinutes);
        }

        if (status.configured && bankAccounts.length) {
            await checkBankBalance(false);
        }

    } catch (error) {
        /* Not set up, or not reachable: the Check Balance screen says why when it is opened. */
    }

    if (me.email) {
        bankTimer = setTimeout(startBankUpdates, bankRefreshMinutes * 60000);
    }
}

init();
