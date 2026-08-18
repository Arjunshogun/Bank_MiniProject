# Banking Website (Java, file-based database)

## Folder layout

```
banking-website/
├── account.txt              ← customer "database" (accountNumber|ownerId|name|pin|balance|label)
├── loans.txt                ← loan applications (created automatically on first submission)
├── transfers.txt            ← transfer history log (created automatically on first transfer)
├── employees.txt            ← bank staff who can log into the employee portal (username|password|name)
├── uploads/                 ← uploaded bank statements & salary slips (created automatically)
├── .vscode/
│   ├── launch.json          ← preconfigured Run/Debug button, correct working dir
│   └── settings.json        ← tells the Java extension src/ is the source folder
└── src/
    └── BankWebServer.java   ← everything: the web server plus the Account, AccountRepository,
                                Loan, LoanRepository, Transfer, TransferRepository, Employee, and
                                EmployeeRepository classes, all as top-level classes in one file
                                (only BankWebServer is public, which is what Java requires the
                                file to be named after)
```

## Setup (one time)

1. **File → Open Folder…** in VS Code → select this `banking-website` folder
   (make sure you open this exact folder, not a parent or subfolder of it).
2. Install the **"Extension Pack for Java"** (Microsoft) if prompted.
3. Confirm a JDK 17+ is installed and detected (`Java: Configure Java Runtime`
   command if needed).

## Run it

Open `src/BankWebServer.java` → click **▶ Run** above `main(...)`, or press `F5`.

Then open **http://localhost:8080**.

Log in with any account from `account.txt`, e.g. Account Number `1001`, PIN `1234`.

## Check the console output every time you run it

On startup you'll see something like:

```
Using database file: C:\...\banking-website\account.txt
Loaded 28 account(s) from the database.
Banking website running at http://localhost:8080
```

- If it says **`Loaded 0 account(s)`**, the file it found is empty or not in
  the `accountNumber|ownerId|name|pin|balance|label` format — open the path
  it printed and check the contents.
- If you see a **`WARNING: could not find 'account.txt'`** block, it lists
  every location it checked and the current working directory — put
  `account.txt` in one of those folders (simplest: the project root shown
  in `.vscode/launch.json`'s `cwd`).

This is the single most common cause of "Account not found" — the server
running but pointed at the wrong (or an empty) `account.txt`.

## How persistence works

- `AccountRepository.java` is the only class that touches `account.txt`
  (`findAll`, `findByAccountNumber`, `update`, `insert`).
- Deposit/withdraw handlers in `BankWebServer.java` update the balance in
  memory, then immediately call `repo.update(account)`, which rewrites
  `account.txt` on disk — open the file after a deposit and you'll see the
  new balance saved.
- File reads/writes are `synchronized` so multiple browser tabs/users at
  once won't corrupt the file.

## Loan applications (customer applies, employee approves)

Two separate logins now exist:

- **Customers** log in at `/` with account number + PIN, same as before.
- **Bank employees** log in separately at `/employee` using a username +
  password from `employees.txt`. Two sample employees are included:
  - `emp1` / `staff123` (Ramesh Kumar)
  - `emp2` / `staff456` (Divya Iyer)

  There's also a "Bank Employee? Log in to the employee portal" link on the
  customer login page.

**Customer side:**
- From the dashboard, click **Apply for a Loan** → enter an amount and a
  purpose, then choose **either a Bank Statement or a Salary Slip** and
  upload one supporting document → submit. This creates a `PENDING` row
  in `loans.txt` and saves the file to `uploads/`.
- Accepted file types: PDF, DOC/DOCX, or an image (JPG/PNG). Max 5 MB
  per file.
- Click **My Loan Applications** to see the status (`PENDING`, `APPROVED`,
  or `REJECTED`) of everything you've applied for, with links to the
  documents you uploaded for each one.

**Employee side:**
- After logging in at `/employee`, the dashboard lists every `PENDING`
  application (with the applicant's account number and name), links to
  their uploaded document (**Bank Statement** or **Salary Slip**,
  whichever the customer chose), and
  **Approve** / **Reject** buttons, plus a history of recently decided
  loans, who decided them, and their documents.
- Clicking **Approve** credits the loan amount straight into the
  customer's balance in `account.txt` (via the same `AccountRepository`
  the deposit/withdraw handlers use) and marks the loan `APPROVED`.
- Clicking **Reject** just marks the loan `REJECTED` — no money moves.
- Once a loan is decided it can't be approved/rejected again.

**Documents (`uploads/` folder):**
- Uploaded files are saved as `loan_<id>_bankstatement.<ext>` and
  `loan_<id>_salaryslip.<ext>` under `uploads/`, created automatically
  on first submission.
- Documents are served from `/loan/document?loanId=<id>&type=bank|salary`.
  Only the applicant (logged in as that customer) or a logged-in
  employee can view a given loan's documents — anyone else gets
  redirected to log in.
- File uploads are parsed with a small hand-written `multipart/form-data`
  parser in `BankWebServer.java` (the built-in `HttpServer` has no
  multipart support of its own).

`loans.txt` format (one line per application):
```
id|accountNumber|amount|purpose|status|appliedDate|decidedBy|bankStatementFile|salarySlipFile
```
It doesn't need to exist beforehand — `LoanRepository` creates it the
first time a customer submits an application.

## One person, multiple accounts + transfers

Every account now has an `ownerId` and a `label` (e.g. `Savings`, `Salary`,
`Joint`) in addition to its account number, name, PIN, and balance:

```
accountNumber|ownerId|name|pin|balance|label
```

Accounts that share the same `ownerId` belong to the same person. A customer
still logs in with one specific account number + PIN (same as before), but
once logged in they can see and transfer to any of their other accounts. In
the sample `account.txt`, a few customers (Arjun, Priya, Karthik, Sneha,
Vikram) each have two accounts to demonstrate this — e.g. Arjun's `1001`
(Savings) and `1101` (Salary) both have `ownerId` `1001`.

**Transfer Money** (new dashboard button):
- Enter any destination account number and an amount, plus an optional note,
  and submit. This works for transferring to someone else's account just as
  well as to your own.
- **Self-transfer**: if you have more than one account, the Transfer page
  lists your other accounts with a **Use** button that fills in the
  destination field for you — a one-click way to move money between your own
  accounts.
- Transfers are rejected with a clear error if the destination account
  doesn't exist, the amount isn't positive, you try to transfer an account
  to itself, or your balance is insufficient.
- Both balances are updated together in a single, synchronized rewrite of
  `account.txt` (`AccountRepository.transfer(...)`), so a transfer can't
  leave money deducted from one account without it landing in the other.
- Click **Transfer History** to see every transfer in or out of the account
  you're logged into, with direction (sent/received), amount, whether it was
  a self-transfer, date, and note. This is logged to `transfers.txt`
  (`id|fromAccount|toAccount|amount|note|date|type`), created automatically
  on the first transfer — it's a history log only, never consulted to decide
  whether a transfer is allowed.

## Notes

- PINs and employee passwords are stored in plain text, same as the
  original console app — fine for a learning project, not for a real bank.
- Sessions and the 4-attempt PIN lockout live in memory only and reset on
  restart; only `account.txt` itself is persistent.
- `AccountRepository.insert(...)` already exists if you want to add a
  "create new account" page later.
