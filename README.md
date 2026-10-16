# Monetra: Your Smart Financial Guardian

> **PayPal AI Hackathon 2026** entry. Monetra helps students and families understand their money, remember household expenses and make safer payments. An AI agent explains, a Java rules engine calculates, you decide, PayPal processes the payment and the server verifies the result.

**AI advises. You decide. PayPal processes. The backend verifies.**

Everything runs in **PayPal Sandbox** with test money. No real money moves.

## The Financial Guardian: one payment, three consequences

Monetra does not just tell you how much you are spending. Before you pay, one analysis looks at the payment three ways at once and tells you what it could mean for your next EMI, next month and your goals.

| Awareness | What Monetra checks | Example |
|---|---|---|
| **Daily spending** | Today's limit, what is already spent, the weekly, monthly and yearly limits | "Your ₹2,000.00 daily limit is exceeded by ₹19,000.00." |
| **Loan repayment** | Your available balance, EMIs, home bills and essential expenses before your next income | "Your projected balance would be -₹1,000.00. That is NOT enough to cover them, so you may fall short." |
| **Savings goals** | The goal you are saving for, your plan and whether you are behind it | "If this payment comes from that savings, the amount you still need rises from ₹40,000.00 to ₹45,000.00." |

- **On the review screen** every warning is one numbered red box (①, ②, ③ ...): daily limit exceeded, payment risk, loans and bills, savings goal, Fraud Guard. Each box says what is wrong and what you could do about it, and each fact appears **once**. The last box is **YOUR DECISION**, where the AI's short advice appears. A payment that fits shows no red box. You can **Go Back**, **Adjust Amount**, **Review Budget** or **Continue Anyway**: the user always decides.
- **On Home**, the two main buttons come first: **Send Money** and **Check Balance**, side by side and styled alike. Then **Safe to Spend Today**: how much you can spend today without touching the EMIs, bills, essential expenses, planned goal savings and emergency buffer that fall before your next income. It counts only your own balance (nothing borrowed) and goes down with every expense you record. Below it, three cards (Daily Budget, Loan Guardian, Savings Goals) open their pages.
- **Loans**: outstanding balance, EMI, due date, interest rate, installments left, frequency and status; an EMI calendar; "Mark EMI paid"; and a next-month forecast (expected income minus EMIs, essentials, bills and planned savings).
- **Savings goals**: target date, planned monthly saving and priority; what to save a day, a week or a month to reach the date; a real shortfall when you fall behind your plan; and advice that never asks for more than is free after EMIs and essentials.
- **My money** (in Spending): available balance, monthly income, income day, essential expenses and an optional buffer. The balance moves by itself with every payment and expense recorded.
- Every figure is calculated in Java with decimal arithmetic (`DailyBudgetService`, `LoanRepaymentService`, `SavingsGoalService`, `FinancialForecastService`, `GuardianAnalysisService`). The AI only puts the finished sentences into words, and its reply is thrown away if it changes a number, softens a broken limit or drops a warning. When a figure is missing, Monetra says what it cannot check instead of guessing.
- **Smart Buy Planner** (Savings → Buy Planner): type what you want and its price, and optionally the day you want it, how much you can save a month and a priority. Monetra follows your money day by day for 12 months (income, every EMI and bill on its date, essentials and goal savings) and tells you whether it is safe to buy today, the earliest safe day, and how much to put aside a day, week or month. "Track this plan" turns it into a savings goal so its progress is tracked.
- **Scam Shield** (Monetra Guardian → AI & Payments → Scam Shield): paste an SMS, a WhatsApp message, a payment request or a link. Java rules look for the tricks Indian payment scams use (PIN or OTP requests, "enter your PIN to receive money", QR refunds, KYC or electricity cut-off threats, lottery prizes, screen-sharing apps, "digital arrest", easy-money jobs, hidden links) and give a LOW, MEDIUM or HIGH risk with the reasons and the suspicious words marked. The message is not saved or logged, and the AI only sees what the rules found, never the message itself. "Is this result wrong?" saves a report, with the message only if you tick that you agree.
- **Guardian Score and badges** (the **B** button, top right of Home): a score out of 100 worked out in Java from your own records with fixed rules, never by the AI: Daily Spending Discipline 25, EMI Preparedness 25, Essential Bills Management 20, Savings Goals Progress 20, Payment Risk Management 10. Missing data earns no points, and with no data at all no score is shown. The screen shows the level and badge (Beginner 0-39, Builder 40-59, Protector 60-79, Champion 80-94, Elite 95-100), each part with its reason, Highest or Lowest first, Badge History (only badges really earned, each once; a lower score never takes one away) and Improve Your Score with estimated points. A new badge sends its own notification at once; a Daily Guardian Update arrives once a day from 9:00 in your own time zone (a background job every 10 minutes, and when the app opens). Both can be switched off in **Settings** (the gear next to the bell), where you also choose how Monetra tells you about something new: **Sound only** (the default), **Vibration only** or **Sound + Vibration**, with a Test button. **Daily and weekly badges** (Badges > Daily / Weekly / Levels): each finished day spent within your daily limit earns a Good Day badge, and a Monday-to-Sunday week with at least 5 Good Days earns a Good Week badge (the week you joined counts only if you joined on its Monday). Every day and week is decided once when it is over and kept, earned or not; the screen shows today so far, the last 7 days, this week, past weeks and your totals. The notification screen has two sides: Payments & Alerts, and Badges (score updates and every badge). Notifications show in the app; the browser can also show them while Monetra is open if you allow it. There is no push service, so nothing arrives while Monetra is closed.
- **Back on every screen**: one Back button at the top left of every screen except Home. It returns to the screen you came from (or Home), keeps what you typed and never sends a payment again.
- **Check Balance and My bank accounts** (Setu Account Aggregator, sandbox): "Link Bank Account" sends you to Setu's own consent screens, where you choose your bank, verify with an OTP and approve. Monetra never sees a bank password, PIN or OTP, and keeps only the masked account number. Check Balance then shows the account, the balance the bank returned and when it was last checked, with a Refresh Balance button. It updates by itself when Monetra opens and then once an hour while it stays open (a consent allows at most one data request an hour). When a check fails, the last real balance stays on screen marked "may be out of date"; a balance is never made up. Several accounts can be linked; one is the main account, and removing an account revokes its consent at Setu first. Without Setu keys the screen says exactly which ones are missing.
- **Notifications** (each sent once, and you can switch each area off): close to or over the daily limit, EMI coming up, due today or not marked paid, a possible shortfall before the next EMI, savings reminders, progress and falling behind. They are created while Monetra is open; nothing is sent in the background.

## HE: Home Expenses

The **HE** button opens home expenses, kept apart from Pay.

- Three payment options, each with its own form: **Daily**, **Monthly** and **Yearly HE Payment**. The form only shows the fields the chosen payment method needs.
- Four payment methods. The server (`GET /api/he/methods`) tells the page what each one really does:

| Method | What happens |
|---|---|
| **Scan & Pay** | Scan a Monetra or UPI QR code with the camera, or choose its picture. The name is shown, you enter the amount, and you approve on PayPal. |
| **My Payment QR** | Your own Monetra QR code, for other people to scan and pay you. |
| **Mobile number** | Type a number (with or without +91) or pick a contact. The number is checked and the name is shown (the registered name on Monetra, or your own contact's name). You approve on PayPal. As on the Pay screen, a number that is not on Monetra can still be paid through PayPal. |
| **Cash** | Recorded only after you confirm you really paid. No money is moved. |

- Scan & Pay and Mobile number reuse the same PayPal Sandbox payment as Send Money, with the same Guardian and Fraud Guard checks first. They are shown as **Successful** only when PayPal confirms them; otherwise **Pending**, **Failed** or **Cancelled**. Pressing Pay Now twice makes one payment.
- **HE reminders** for daily, monthly and yearly expenses, each switched on by itself with its own day or date and time.
- **HE payment history** with amount, who was paid, date, method, transaction ID and status. Tap a row to see its details. HE payments do not appear in the ordinary Transactions list.

## The one main button: Monetra Guardian

The **Monetra Guardian** button (top left) opens one place with two sections that cover the three main features. Everything else in the app (Home, Pay, Spending, Loans, Savings, Transactions, Forecast, Fraud Guard, QR, bank transfer) is unchanged.

| Section | Feature | What it does |
|---|---|---|
| **AI & Payments** | **AI Financial Guardian** | A chat agent that calls tools on the Java backend (`check_budget`, `get_upcoming_expenses`, `check_affordability`, `prepare_payment`, `get_home_contributions`, `prepare_reminder`). It answers from your real records and can **prepare** a payment or a reminder, but only you can confirm. |
| **AI & Payments** | **PayPal Payment System** | PayPal Orders (send), **PayPal Payouts** (the receiver gets the money in their PayPal account), **verified webhooks**, a payments table with status and payout status, and a "Check with PayPal" recovery button. |
| **Family & Home** | **Home Expense Guardian** | Recurring household expenses (rent, bills, money for home) that are daily, weekly, monthly, yearly or one time. Upcoming, due today, overdue and paid tracking, reminders, a daily summary and a monthly report. Payments you mark yourself are labelled "not verified"; **Pay with Monetra** opens HE with the bill filled in: paid through PayPal, it is marked paid when PayPal confirms it; recorded as cash, it is marked paid as your own word. The bill being paid is not counted twice in the Guardian check. |

**Send Money** and **HE** have one box for **a name or a mobile number**. As you type, matching contacts appear under it with their name and number, like a phone's dialler: people you saved, people you paid or were paid by, and **your own phone contacts**. Tap *Add contacts from your phone* in the list: on Android Chrome it opens the phone's contact list, on other browsers you choose the contacts file (`.vcf`) exported from your phone or Google Contacts. (You cannot search all Monetra users, by design. Phone contacts are stored only for you and *Remove* deletes them.)

**App-style screens:** the page itself never scrolls. Each screen is split into small tabs (for example Loans: Guardian, EMI calendar, My loans, Add loan), and only a long list scrolls inside its own panel.

Other features: pay by **mobile number, QR code scan or bank account** (the name and the bank are detected for you), **My QR** to get paid, **Fraud Guard**, **Spending Forecast** with AI explanation, loans and EMIs, savings goals, notifications and a transaction history of sent **and received** payments.

## Judging quick start (about 3 minutes)

You need **Java 21**. Nothing else to install (the Maven wrapper is included).

```bash
# 1. Demo accounts + sample data, no email needed
export MONETRA_SEED_DEMO_USERS=true          # Windows PowerShell: $env:MONETRA_SEED_DEMO_USERS = 'true'

# 2. PayPal Sandbox credentials (see "PayPal Sandbox setup" below)
export PAYPAL_CLIENT_ID=...                  # Windows PowerShell: $env:PAYPAL_CLIENT_ID = '...'
export PAYPAL_CLIENT_SECRET=...

# 3. Optional: turn on the AI. A FREE option is in the "Free AI" section below. Example with Google Gemini (free tier):
export AI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/
export AI_MODEL=<a current Gemini flash model id>
export AI_API_KEY=<your free key from https://aistudio.google.com/apikey>

# 4. Run
./mvnw spring-boot:run                       # Windows: .\mvnw.cmd spring-boot:run
```

Open <http://localhost:8081>.

**Keeping your keys private (`.env`).** Keys never go in the code or in any uploaded file. Monetra reads them from environment variables, and on a Windows computer a private file called `.env` fills them in:

1. Copy `.env.example` to a new file named `.env` in the project folder (next to `pom.xml`).
2. Fill in the values you have (PayPal, Setu, Groq, Claude). An empty value just switches that feature off, and Monetra says so.
3. Start with `powershell -ExecutionPolicy Bypass -File .\start-local.ps1`. It reads `.env`, never prints a value, and never replaces a value you already set in the terminal. Add `-CheckOnly` to see which keys were found (names only) without starting Monetra.

`.env` is listed in `.gitignore`, so it is never uploaded. `.env.example` holds no real values and is safe to share. `start-local.ps1` is a local helper kept out of Git; on other systems export the same names as environment variables, as shown above. On Render, set the same names in the dashboard (`render.yaml` lists them). If a key was ever shown or uploaded, create a new one at the provider and put the new one in `.env`.

Only **one copy** can run at a time: the database file is locked, and a second copy stops with "Database may be already in use". Stop the first one with Ctrl-C (it shuts down cleanly), then start again.

**Demo accounts** (created only when `MONETRA_SEED_DEMO_USERS=true`):

| Email | Name | Mobile | Notes |
|---|---|---|---|
| `demo1@monetra.test` | Asha Demo | `9000000001` | Has limits, an EMI, a goal and sample expenses. Plays the sender. |
| `demo2@monetra.test` | Ravi Demo | `9000000002` | Empty. Plays the person to pay and be paid by. |

The demo password is `MonetraDemo#2026` (change it with `MONETRA_DEMO_PASSWORD`). These accounts are for testing only. Do not enable the seed on a real deployment.

**Suggested demo flow (uses all three main features)**

1. Log in as `demo1` in one window and as `demo2` in a private window.
2. **demo2:** Monetra Guardian → AI & Payments → *Get paid to your PayPal account* → add your PayPal Sandbox personal email.
3. **demo1:** Monetra Guardian → AI & Payments → ask "Can I afford ₹800 for shoes? Prepare it for 9000000002." The agent checks the budget, home expenses and EMIs, then **prepares** the payment.
4. Press **Review & confirm**. The Guardian shows the budget check and any Fraud Guard notice, and you decide: **Go Back** or **Skip & Continue**.
5. Approve on PayPal Sandbox. The payment is captured and verified, the receiver is **paid out** to their PayPal email, and both people are notified. **demo2** sees it in Transactions as received.
6. **Home Expense Guardian:** add "Money for home ₹3,000 monthly", see it due, and mark it paid.

Without PayPal credentials everything except the final PayPal step still works. The app says clearly that PayPal is not configured and never fakes a payment. Without an AI key the chat says the AI is not set up, and the rest keeps working with rule-based text.

## PayPal Sandbox setup

1. Sign in at <https://developer.paypal.com/> and open **Apps & Credentials** (Sandbox).
2. **Create App** (type Merchant). Copy the **Client ID** and **Secret**. In the app's **Features**, tick **Payouts**.
3. Under **Testing Tools → Sandbox Accounts**, open the **Personal** account to get a test buyer email and password. You use it on the PayPal page when you pay. A receiver adds their own Sandbox personal email in *Monetra Guardian → AI & Payments → Get paid to your PayPal account*.
4. Set `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` as environment variables (on Windows, put them in `.env`: see "Keeping your keys private" above). **Never commit them.**
5. **Webhooks (optional but recommended):** in the dashboard create a webhook for your app with the URL `https://YOUR-SERVER/api/paypal/webhook` and the events `PAYMENT.CAPTURE.COMPLETED` and `PAYMENT.PAYOUTS-ITEM.*`. Copy its **Webhook ID** into `PAYPAL_WEBHOOK_ID`. PayPal can only reach a public address, so use the Render deployment (or a tunnel) for this. Without it the webhook endpoint answers 503, and the "Check with PayPal" button still recovers a payment.

## How PayPal and AI are used

**PayPal (central to the product)**

- **Orders API v2**: `POST /v2/checkout/orders` (create), `.../capture`, `GET /v2/checkout/orders/{id}` (recovery).
- **Payouts API**: `POST /v1/payments/payouts` sends the captured amount to the receiver's PayPal email, with a stable batch id so a retry can never pay twice.
- **Webhooks**: signature verified through `POST /v1/notifications/verify-webhook-signature`. `PAYMENT.CAPTURE.COMPLETED` finishes a payment even if the browser was closed, and `PAYMENT.PAYOUTS-ITEM.*` updates the payout. Handling an event twice is harmless.
- The **server** creates orders and decides the amount. The browser only suggests it. PayPal credentials never reach the browser.
- A payment becomes **COMPLETED only if** PayPal reports the order and capture as completed, the currency is USD **and the captured amount equals what the server created**. Capturing twice never charges twice.
- Only the sender can capture an order. A receiver on Monetra sees the payment only after confirmation.
- Bank transfers keep only the IFSC and the last 4 digits of the account number, and are simulated in the Sandbox (no bank payout is made).
- Cross-border awareness: the review screen shows the USD charge and the demo exchange rate (`monetra.inr-per-usd`, default 88).

**AI (free options or Claude, optional key)**

- **AI Financial Guardian agent** with tool calling. Every number comes from a tool that does the arithmetic in Java on the user's own saved data.
- The agent can only **prepare** a payment or a reminder. It cannot send money or save anything. The app shows what was prepared and the user confirms. Tool outputs and user text are treated as data, not instructions.
- **AI notice while paying**: before every payment the AI sees the user's spending limits, every loan and EMI, the savings goals and the household bills due soon (all worked out in Java from the saved records, not sent by the browser) and writes a short notice. It only advises; the user decides.
- It also parses "Pay 9876543210 500 for lunch" and explains the month-end forecast.
- Works with **free AI providers** (Google Gemini free tier, Groq free tier, Ollama on your own computer) or Claude. Without any AI key the app falls back to rule-based text and still works.

## Free AI (no payment needed)

Monetra's AI works with any provider that speaks the common "OpenAI chat" format, so you can use a free one. Pick one, set three variables and restart. Free tiers and model names change, so check each provider's page for the current model id and limits.

| Option | Cost | Set these |
|---|---|---|
| **Google Gemini** (free tier, key from [Google AI Studio](https://aistudio.google.com/apikey)) | Free tier with rate limits | `AI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/`, `AI_MODEL=<a current Gemini flash model id>`, `AI_API_KEY=<your key>` |
| **Groq** (recommended online option: free tier, key from [console.groq.com](https://console.groq.com)) | Free tier with rate limits | `AI_BASE_URL=https://api.groq.com/openai/v1`, `AI_MODEL=openai/gpt-oss-120b` (or `openai/gpt-oss-20b`; `llama-3.3-70b-versatile` was retired in August 2026), `AI_API_KEY=<your key>`. On Windows, just put the key in `.env` as `GROQ_API_KEY`: `start-local.ps1` turns it into these three variables. `render.yaml` already sets the address and model, so on Render only `AI_API_KEY` is needed. |
| **Ollama** (runs on your own computer, [ollama.com](https://ollama.com)) | Free and unlimited, no key, works offline | install Ollama, run `ollama pull llama3.2`, then `AI_BASE_URL=http://localhost:11434/v1`, `AI_MODEL=llama3.2` |

Windows PowerShell example:

```bash
$env:AI_BASE_URL = 'https://generativelanguage.googleapis.com/v1beta/openai/'
$env:AI_MODEL = 'paste-a-current-gemini-model-id'
$env:AI_API_KEY = 'paste-your-free-key'
.\mvnw.cmd spring-boot:run
```

The chat needs a model that supports **tool calling**. If a small model answers badly, try a larger one. Reasoning models (gpt-oss) are asked to think briefly (`reasoning_effort: low`) so their short notices are not cut off. Never commit your key.

## Security rules in the code

- Private endpoints identify the user from the **session**, never from the request body.
- The server recomputes the budget and fraud checks when creating the order, not only in the page.
- Receivers see sent-to-them payments only after PayPal confirms. The full bank account number is never stored.
- **Sign-in is rate limited:** after 8 wrong passwords from one address for one email, sign-in pauses for 15 minutes (a correct password clears the count, and other people are not affected). An unknown email gets the same answer and takes the same time as a wrong password. Passwords longer than 72 characters are refused (BCrypt reads only 72).
- **Sign-up codes:** 6 requests per address in 15 minutes, 5 tries per code, and unfinished sign-ups are forgotten when their code expires. The code is never printed unless `MONETRA_LOG_OTP=true` (local development only).
- **Money in saved records is checked on the server:** limits, expense, income, savings, loan, goal and profile amounts must be numbers from 0 to 1,000,000,000, and a payment of nothing, a negative or text amount, an unknown payment method or a payment to yourself is refused with a clear message.
- The login cookie is `HttpOnly` and `SameSite=Lax`; set `MONETRA_COOKIE_SECURE=true` when the site is served over https so it is never sent over plain http.
- A PayPal webhook is accepted only when `PAYPAL_WEBHOOK_ID` is set and PayPal's signature checks out.

## Architecture

```
Browser (HTML, CSS, JavaScript)  ->  Spring Boot 4 (Java 21)  ->  PayPal Sandbox (Orders, Payouts, Webhooks)
   index.html / app.js / app.css        REST /api/*                AI provider (optional, free or Claude)
                                        H2 file database (JPA)
```

| Class | Role |
|---|---|
| `OtpAuthController`, `SessionUser` | Email-OTP registration, login, server-side session cookie |
| `PaymentController`, `PaymentService`, `PaypalClient` | Recipient lookup, PayPal orders, capture, payouts, recovery, history |
| `PaypalWebhookController` | Verified PayPal webhooks |
| `BudgetRules`, `FraudRules`, `HomeExpenseRules` | Deterministic, unit-tested rules |
| `GuardianAnalysisService`, `DailyBudgetService`, `LoanRepaymentService`, `SavingsGoalService`, `FinancialForecastService` | The Financial Guardian: one combined analysis of limits, loans and goals, the dashboard figures and the notifications |
| `SetuAaClient`, `BankService`, `BankController`, `BankLink`, `BankAccount` | My bank accounts and Check Balance through Setu's Account Aggregator (consents, data sessions, balances) |
| `PurchasePlannerService` | Smart Buy Planner: the day-by-day money plan and the earliest safe day to buy |
| `ScamShieldService`, `ScamReport` | Scam Shield rules, and "this result is wrong" reports (the message only with consent) |
| `NumberGuard`, `RecordsValidator` | Checks on what the AI writes and on the loan, goal and money figures a user saves |
| `HeController`, `HePayment` | HE: home expense payments and their history |
| `AgentController`, `AgentTools`, `AiService`, `OpenAiCompatibleClient`, `AnthropicClient` | The AI agent and its tools |
| `HomeController`, `HomeExpenseService` | Home Expense Guardian |
| `ContactController`, `ContactService` | Contacts search and phone contacts |
| `QrController`, `NotificationController`, `ProfileController`, `DataController`, `DemoData` | QR, notifications, PayPal email, per-user records, demo accounts |

## Run the tests

```bash
./mvnw test
```

The automated tests cover the budget, fraud and schedule rules, QR and IFSC parsing, login and per-user data isolation, the **whole payment journey against a fake PayPal server** (create, capture, history for both people, notifications, payout to the receiver's PayPal email, verified and rejected webhooks, no double processing), the **AI agent against fake Claude and fake free-provider servers** (tool calls, prepared-only payments and reminders, errors), household expenses (schedules, paid and overdue, reminders, reports), contacts privacy, and the **Financial Guardian** (daily limit, loan repayment, next-month forecast and savings-goal sums; one payment checked against all three; notifications sent once; the HE payment methods, with cash recorded only on confirmation and PayPal ones verified; refused bad figures; and one user never seeing another's loans, goals or forecasts), **Safe to Spend Today**, the **Smart Buy Planner** (worked-out dates and amounts), **Scam Shield** (risk levels, no message kept without consent) review boxes that never say the same thing twice, the **Guardian Score** with its levels, daily and weekly badges and notifications, **sign-up and sign-in** (the emailed code, wrong codes, the guess limits), **bad money** (zero, negative, huge or text amounts on payments and saved records), the backup AI when the online one cannot be reached, and the number check that rejects AI answers with invented figures.

## API collection

Import `postman/monetra.postman_collection.json` into **Postman**. Log in first; Postman keeps the session cookie.

## Deploy on Render

`render.yaml` and `Dockerfile` are included. Create a Blueprint from the repo, then set `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_WEBHOOK_ID` and (optionally) the AI variables in the Render dashboard. Also set `MONETRA_DEMO_PASSWORD` (your own password for the demo accounts, shared with the judges in the Devpost testing instructions) and `MONETRA_COOKIE_SECURE=true`. Leave `MONETRA_LOG_OTP` unset online.

The H2 file database lives on the container disk, so **data resets when the server restarts or is redeployed**, and the free plan restarts it after it sleeps. The demo accounts and their sample data are created again at every start, so judges can always sign in; anything else they add is temporary. To keep data, point `MONETRA_DB_URL` at a database that survives restarts (this needs the matching JDBC driver added to the project; the app is tested on H2 only). Run a **single instance**.

## Configuration

| Variable | Purpose |
|---|---|
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | PayPal Sandbox credentials |
| `PAYPAL_WEBHOOK_ID` | Webhook id, so PayPal events are accepted |
| `SETU_AA_CLIENT_ID`, `SETU_AA_CLIENT_SECRET`, `SETU_AA_PRODUCT_INSTANCE_ID` | Setu Account Aggregator keys for Check Balance (from bridge.setu.co, your FIU data product) |
| `SETU_AA_BASE_URL` | Optional. `https://fiu-sandbox.setu.co` (the default) or Setu's production gateway |
| `AI_BASE_URL`, `AI_MODEL`, `AI_API_KEY` | A FREE AI (Gemini, Groq, Ollama): see "Free AI" (optional) |
| `AI_FALLBACK_BASE_URL`, `AI_FALLBACK_MODEL` | An AI on this computer (Ollama) that answers only when the online AI cannot be reached. `start-local.ps1` sets them when Ollama is installed (optional) |
| `GROQ_API_KEY` | Read only by `start-local.ps1` (from `.env`): it sets `AI_BASE_URL`, `AI_MODEL` and `AI_API_KEY` for Groq. Not a Spring setting |
| `ANTHROPIC_API_KEY` | Claude instead (paid, optional) |
| `AI_PROVIDER` | `openai` or `anthropic` to force one (empty chooses automatically) |
| `MONETRA_SEED_DEMO_USERS` | `true` creates the demo accounts |
| `MONETRA_DEMO_PASSWORD` | Password for the demo accounts |
| `MONETRA_MAIL_USERNAME`, `MONETRA_MAIL_PASSWORD` | Gmail SMTP (App Password) for registration OTP emails (optional) |
| `MONETRA_COOKIE_SECURE` | `true` when the site is served over https (online): the login cookie is then never sent over plain http |
| `MONETRA_LOG_OTP` | `true` prints the OTP in the server console when email is not set up (local development only) |
| `PORT` | HTTP port (default 8081) |
| `MONETRA_DB_URL` | JDBC URL (default `jdbc:h2:file:./data/monetra`) |

## Honest limitations

- **Sandbox only.** Test money. Going live needs a live PayPal merchant account and review.
- **Check Balance uses Setu's sandbox:** its accounts and balances are Setu's test data, not a real bank. Reading real bank accounts through the Account Aggregator network needs Setu's production approval, and FIUs must be regulated entities (RBI, SEBI, IRDAI or PFRDA).
- Payouts need **Payouts enabled** on your PayPal app and a **balance** in the Sandbox business account. If a payout fails, the payment stays confirmed and the receiver sees the payout status, with a retry button.
- A mobile number or bank account is used to **identify** the recipient inside Monetra. Monetra does not send real UPI or bank transfers. In HE, Cash is a record of what you paid yourself. HE has no bank transfer option: PayPal cannot pay a bank account.
- Account balances and spending are **entered by you**, not read from a bank.
- The per-payment lock is in-process, so run a single server instance.
- Live QR camera scanning needs a browser with `BarcodeDetector` (Chrome, Edge, Android). On any browser you can upload a QR image instead.

## Not a financial advisor

Monetra is a decision-support tool. It cannot guarantee that a recommendation is right or that you will avoid financial loss.

## License

MIT. See [LICENSE](LICENSE).
