# Monetra: Your Smart Financial Guardian

> **PayPal AI Hackathon 2026** entry. Monetra helps students and families understand their money, remember household expenses and make safer payments. An AI agent explains, a Java rules engine calculates, you decide, PayPal processes the payment and the server verifies the result.

**AI advises. You decide. PayPal processes. The backend verifies.**

Everything runs in **PayPal Sandbox** with test money. No real money moves.

## The one main button: Monetra Guardian

The **Monetra Guardian** button (top left) opens one place with two sections that cover the three main features. Everything else in the app (Home, Pay, Spending, Loans, Savings, Transactions, Forecast, Fraud Guard, QR, bank transfer) is unchanged.

| Section | Feature | What it does |
|---|---|---|
| **AI & Payments** | **AI Financial Guardian** | A chat agent that calls tools on the Java backend (`check_budget`, `get_upcoming_expenses`, `check_affordability`, `prepare_payment`, `get_home_contributions`, `prepare_reminder`). It answers from your real records and can **prepare** a payment or a reminder, but only you can confirm. |
| **AI & Payments** | **PayPal Payment System** | PayPal Orders (send), **PayPal Payouts** (the receiver gets the money in their PayPal account), **verified webhooks**, a payments table with status and payout status, and a "Check with PayPal" recovery button. |
| **Family & Home** | **Home Expense Guardian** | Recurring household expenses (rent, bills, money for home) that are daily, weekly, monthly, yearly or one time. Upcoming, due today, overdue and paid tracking, reminders, a daily summary and a monthly report. Payments you mark yourself are labelled "not verified"; payments made through Monetra are marked paid when PayPal confirms them. |

**Send Money** also has **contacts search**: people you saved and people you paid or were paid by. (You cannot search all Monetra users, by design.)

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
4. Set `PAYPAL_CLIENT_ID` and `PAYPAL_CLIENT_SECRET` as environment variables. **Never commit them.**
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
- It also explains a payment's budget impact, parses "Pay 9876543210 500 for lunch", and explains the month-end forecast.
- Works with **free AI providers** (Google Gemini free tier, Groq free tier, Ollama on your own computer) or Claude. Without any AI key the app falls back to rule-based text and still works.

## Free AI (no payment needed)

Monetra's AI works with any provider that speaks the common "OpenAI chat" format, so you can use a free one. Pick one, set three variables and restart. Free tiers and model names change, so check each provider's page for the current model id and limits.

| Option | Cost | Set these |
|---|---|---|
| **Google Gemini** (free tier, key from [Google AI Studio](https://aistudio.google.com/apikey)) | Free tier with rate limits | `AI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/`, `AI_MODEL=<a current Gemini flash model id>`, `AI_API_KEY=<your key>` |
| **Groq** (free tier, key from [console.groq.com](https://console.groq.com)) | Free tier with rate limits | `AI_BASE_URL=https://api.groq.com/openai/v1`, `AI_MODEL=llama-3.3-70b-versatile` (or another model that supports tools), `AI_API_KEY=<your key>` |
| **Ollama** (runs on your own computer, [ollama.com](https://ollama.com)) | Free, no key, no internet | install Ollama, run `ollama pull llama3.1`, then `AI_BASE_URL=http://localhost:11434/v1`, `AI_MODEL=llama3.1` |

Windows PowerShell example:

```bash
$env:AI_BASE_URL = 'https://generativelanguage.googleapis.com/v1beta/openai/'
$env:AI_MODEL = 'paste-a-current-gemini-model-id'
$env:AI_API_KEY = 'paste-your-free-key'
.\mvnw.cmd spring-boot:run
```

The chat needs a model that supports **tool calling**. If a small model answers badly, try a larger one. Never commit your key.

## Security rules in the code

- Private endpoints identify the user from the **session**, never from the request body.
- The server recomputes the budget and fraud checks when creating the order, not only in the page.
- Receivers see sent-to-them payments only after PayPal confirms. The full bank account number is never stored.

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
| `AgentController`, `AgentTools`, `AiService`, `OpenAiCompatibleClient`, `AnthropicClient` | The AI agent and its tools |
| `HomeController`, `HomeExpenseService` | Home Expense Guardian |
| `ContactController`, `ContactService` | Contacts search |
| `QrController`, `NotificationController`, `ProfileController`, `DataController`, `DemoData` | QR, notifications, PayPal email, per-user records, demo accounts |

## Run the tests

```bash
./mvnw test
```

The automated tests cover the budget, fraud and schedule rules, QR and IFSC parsing, login and per-user data isolation, the **whole payment journey against a fake PayPal server** (create, capture, history for both people, notifications, payout to the receiver's PayPal email, verified and rejected webhooks, no double processing), the **AI agent against fake Claude and fake free-provider servers** (tool calls, prepared-only payments and reminders, errors), household expenses (schedules, paid and overdue, reminders, reports) and contacts privacy.

## API collection

Import `postman/monetra.postman_collection.json` into **Postman**. Log in first; Postman keeps the session cookie.

## Deploy on Render

`render.yaml` and `Dockerfile` are included. Create a Blueprint from the repo, then set `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET`, `PAYPAL_WEBHOOK_ID` and (optionally) the AI variables in the Render dashboard. The H2 file database lives on the container disk, so data resets on redeploy. That is fine for a demo.

## Configuration

| Variable | Purpose |
|---|---|
| `PAYPAL_CLIENT_ID`, `PAYPAL_CLIENT_SECRET` | PayPal Sandbox credentials |
| `PAYPAL_WEBHOOK_ID` | Webhook id, so PayPal events are accepted |
| `AI_BASE_URL`, `AI_MODEL`, `AI_API_KEY` | A FREE AI (Gemini, Groq, Ollama): see "Free AI" (optional) |
| `ANTHROPIC_API_KEY` | Claude instead (paid, optional) |
| `AI_PROVIDER` | `openai` or `anthropic` to force one (empty chooses automatically) |
| `MONETRA_SEED_DEMO_USERS` | `true` creates the demo accounts |
| `MONETRA_DEMO_PASSWORD` | Password for the demo accounts |
| `MONETRA_MAIL_USERNAME`, `MONETRA_MAIL_PASSWORD` | Gmail SMTP (App Password) for registration OTP emails (optional) |
| `MONETRA_LOG_OTP` | `true` prints the OTP in the server console when email is not set up (local development only) |
| `PORT` | HTTP port (default 8081) |
| `MONETRA_DB_URL` | JDBC URL (default `jdbc:h2:file:./data/monetra`) |

## Honest limitations

- **Sandbox only.** Test money. Going live needs a live PayPal merchant account and review.
- Payouts need **Payouts enabled** on your PayPal app and a **balance** in the Sandbox business account. If a payout fails, the payment stays confirmed and the receiver sees the payout status, with a retry button.
- A mobile number or bank account is used to **identify** the recipient inside Monetra. Monetra does not send real UPI or bank transfers.
- Account balances and spending are **entered by you**, not read from a bank.
- The per-payment lock is in-process, so run a single server instance.
- Live QR camera scanning needs a browser with `BarcodeDetector` (Chrome, Edge, Android). On any browser you can upload a QR image instead.

## Not a financial advisor

Monetra is a decision-support tool. It cannot guarantee that a recommendation is right or that you will avoid financial loss.

## License

MIT. See [LICENSE](LICENSE).
