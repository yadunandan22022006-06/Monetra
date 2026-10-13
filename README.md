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

- **On the review screen** each warning that applies is shown in its own red box (Daily limit exceeded, Payment risk, Loan repayment risk, Savings goal impact, Your decision), with a risk level for each area and an overall status. A payment that fits shows no warning. You can **Go Back**, **Adjust Amount**, **Review Budget** or **Continue Anyway**: the user always decides.
- **On Home** three cards (Daily Budget, Loan Guardian, Savings Goals) open their pages.
- **Loans**: outstanding balance, EMI, due date, interest rate, installments left, frequency and status; an EMI calendar; "Mark EMI paid"; and a next-month forecast (expected income minus EMIs, essentials, bills and planned savings).
- **Savings goals**: target date, planned monthly saving and priority; what to save a day, a week or a month to reach the date; a real shortfall when you fall behind your plan; and advice that never asks for more than is free after EMIs and essentials.
- **My money** (in Spending): available balance, monthly income, income day, essential expenses and an optional buffer. The balance moves by itself with every payment and expense recorded.
- Every figure is calculated in Java with decimal arithmetic (`DailyBudgetService`, `LoanRepaymentService`, `SavingsGoalService`, `FinancialForecastService`, `GuardianAnalysisService`). The AI only puts the finished sentences into words, and its reply is thrown away if it changes a number, softens a broken limit or drops a warning. When a figure is missing, Monetra says what it cannot check instead of guessing.
- **Notifications** (each sent once, and you can switch each area off): close to or over the daily limit, EMI coming up, due today or not marked paid, a possible shortfall before the next EMI, savings reminders, progress and falling behind. They are created while Monetra is open; nothing is sent in the background.

## HE: Home Expenses

The **HE** button opens home expenses, kept apart from Pay.

- Three payment options, each with its own form: **Daily**, **Monthly** and **Yearly HE Payment**. The form only shows the fields the chosen payment method needs.
- Five payment methods. The server (`GET /api/he/methods`) tells the page what each one really does:

| Method | What happens |
|---|---|
| **Scan & Pay** | Scan a Monetra or UPI QR code with the camera, or choose its picture. The name is shown, you enter the amount, and you approve on PayPal. |
| **My Payment QR** | Your own Monetra QR code, for other people to scan and pay you. |
| **Mobile number** | Type a number (with or without +91) or pick a contact. The number is checked and the name is shown (the registered name on Monetra, or your own contact's name). You approve on PayPal. As on the Pay screen, a number that is not on Monetra can still be paid through PayPal. |
| **Bank transfer** | PayPal cannot send money to a bank account, so Monetra does **not** start a bank transfer. You make it in your bank's app (NEFT, IMPS, RTGS or UPI) and record it here. Only the last 4 digits of an account are kept. |
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
| **Family & Home** | **Home Expense Guardian** | Recurring household expenses (rent, bills, money for home) that are daily, weekly, monthly, yearly or one time. Upcoming, due today, overdue and paid tracking, reminders, a daily summary and a monthly report. Payments you mark yourself are labelled "not verified"; payments made through Monetra are marked paid when PayPal confirms them. |

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
- **AI notice while paying**: before every payment the AI sees the user's spending limits, every loan and EMI, the savings goals and the household bills due soon (all worked out in Java from the saved records, not sent by the browser) and writes a short notice. It only advises; the user decides.
- It also parses "Pay 9876543210 500 for lunch" and explains the month-end forecast.
- Works with **free AI providers** (Google Gemini free tier, Groq free tier, Ollama on your own computer) or Claude. Without any AI key the app falls back to rule-based text and still works.

## Free AI (no payment needed)

Monetra's AI works with any provider that speaks the common "OpenAI chat" format, so you can use a free one. Pick one, set three variables and restart. Free tiers and model names change, so check each provider's page for the current model id and limits.

| Option | Cost | Set these |
|---|---|---|
| **Google Gemini** (free tier, key from [Google AI Studio](https://aistudio.google.com/apikey)) | Free tier with rate limits | `AI_BASE_URL=https://generativelanguage.googleapis.com/v1beta/openai/`, `AI_MODEL=<a current Gemini flash model id>`, `AI_API_KEY=<your key>` |
| **Groq** (free tier, key from [console.groq.com](https://console.groq.com)) | Free tier with rate limits | `AI_BASE_URL=https://api.groq.com/openai/v1`, `AI_MODEL=llama-3.3-70b-versatile` (or another model that supports tools), `AI_API_KEY=<your key>` |
| **Ollama** (runs on your own computer, [ollama.com](https://ollama.com)) | Free and unlimited, no key, works offline | install Ollama, run `ollama pull llama3.2`, then `AI_BASE_URL=http://localhost:11434/v1`, `AI_MODEL=llama3.2` |

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
| `GuardianAnalysisService`, `DailyBudgetService`, `LoanRepaymentService`, `SavingsGoalService`, `FinancialForecastService` | The Financial Guardian: one combined analysis of limits, loans and goals, the dashboard figures and the notifications |
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

The automated tests cover the budget, fraud and schedule rules, QR and IFSC parsing, login and per-user data isolation, the **whole payment journey against a fake PayPal server** (create, capture, history for both people, notifications, payout to the receiver's PayPal email, verified and rejected webhooks, no double processing), the **AI agent against fake Claude and fake free-provider servers** (tool calls, prepared-only payments and reminders, errors), household expenses (schedules, paid and overdue, reminders, reports), contacts privacy, and the **Financial Guardian** (daily limit, loan repayment, next-month forecast and savings-goal sums; one payment checked against all three; notifications sent once; the HE payment methods, with cash and bank transfers recorded only on confirmation and PayPal ones verified; refused bad figures; and one user never seeing another's loans, goals or forecasts).

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
- A mobile number or bank account is used to **identify** the recipient inside Monetra. Monetra does not send real UPI or bank transfers. In HE, Bank transfer and Cash are records of what you paid yourself; a real bank transfer would need a different payment provider.- Account balances and spending are **entered by you**, not read from a bank.
- The per-payment lock is in-process, so run a single server instance.
- Live QR camera scanning needs a browser with `BarcodeDetector` (Chrome, Edge, Android). On any browser you can upload a QR image instead.

## Not a financial advisor

Monetra is a decision-support tool. It cannot guarantee that a recommendation is right or that you will avoid financial loss.

## License

MIT. See [LICENSE](LICENSE).
