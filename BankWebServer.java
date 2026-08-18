
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;

public class BankWebServer {

    static final int PORT = 8080;
    static final int MAX_ATTEMPTS = 4;
    static final String UPLOAD_DIR = "uploads";
    static final long MAX_UPLOAD_BYTES = 5L * 1024 * 1024; // 5 MB per file
    static final Set<String> ALLOWED_DOC_EXTENSIONS = Set.of("pdf", "jpg", "jpeg", "png", "doc", "docx");

    static final AccountRepository repo = new AccountRepository("account.txt");
    static final LoanRepository loanRepo = new LoanRepository("loans.txt");
    static final EmployeeRepository employeeRepo = new EmployeeRepository("employees.txt");
    static final TransferRepository transferRepo = new TransferRepository("transfers.txt");

    static final Map<String, Long> sessions = new ConcurrentHashMap<>();
    static final Map<Long, Integer> failedAttempts = new ConcurrentHashMap<>();
    static final Set<Long> lockedAccounts = ConcurrentHashMap.newKeySet();

    // employee session cookie -> username, kept separate from customer sessions
    static final Map<String, String> employeeSessions = new ConcurrentHashMap<>();

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(PORT), 0);

        server.createContext("/", BankWebServer::handleLoginPage);
        server.createContext("/login", BankWebServer::handleLogin);
        server.createContext("/dashboard", BankWebServer::handleDashboard);
        server.createContext("/deposit", BankWebServer::handleDeposit);
        server.createContext("/withdraw", BankWebServer::handleWithdraw);
        server.createContext("/details", BankWebServer::handleDetails);
        server.createContext("/transfer", BankWebServer::handleTransfer);
        server.createContext("/transfer/history", BankWebServer::handleTransferHistory);
        server.createContext("/logout", BankWebServer::handleLogout);

        server.createContext("/loan/apply", BankWebServer::handleLoanApply);
        server.createContext("/loan/status", BankWebServer::handleLoanStatus);
        server.createContext("/loan/document", BankWebServer::handleLoanDocument);

        server.createContext("/employee", BankWebServer::handleEmployeeLoginPage);
        server.createContext("/employee/login", BankWebServer::handleEmployeeLogin);
        server.createContext("/employee/dashboard", BankWebServer::handleEmployeeDashboard);
        server.createContext("/employee/loan/approve", BankWebServer::handleLoanApprove);
        server.createContext("/employee/loan/reject", BankWebServer::handleLoanReject);
        server.createContext("/employee/logout", BankWebServer::handleEmployeeLogout);

        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        int accountCount = repo.findAll().size();
        System.out.println("Loaded " + accountCount + " account(s) from the database.");
        if (accountCount == 0) {
            System.out.println("No accounts loaded - logins will fail with 'Account not found'.");
            System.out.println("See the WARNING above (if any) for where account.txt was expected.");
        }
        int employeeCount = employeeRepo.findAll().size();
        System.out.println("Loaded " + employeeCount + " employee(s) for the employee portal.");

        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
        } catch (IOException e) {
            System.out.println("WARNING: could not create uploads directory: " + e.getMessage());
        }

        System.out.println("Banking website running at http://localhost:" + PORT);
    }

    static void handleLoginPage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            notFound(ex);
            return;
        }

        Long accNo = currentAccount(ex);
        if (accNo != null) {
            redirect(ex, "/dashboard");
            return;
        }

        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String error = q.get("error");

        String body = loginPageHtml(error);
        sendHtml(ex, 200, body);
    }

    static void handleLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            notFound(ex);
            return;
        }

        Map<String, String> form = parseForm(ex);
        long accountNumber;
        int pin;
        try {
            accountNumber = Long.parseLong(form.getOrDefault("accountNumber", "").trim());
            pin = Integer.parseInt(form.getOrDefault("pin", "").trim());
        } catch (NumberFormatException e) {
            redirect(ex, "/?error=" + encode("Please enter a valid account number and PIN."));
            return;
        }

        if (lockedAccounts.contains(accountNumber)) {
            redirect(ex, "/?error=" + encode("This account is locked due to too many failed attempts."));
            return;
        }

        Account account = repo.findByAccountNumber(accountNumber);
        if (account == null) {
            redirect(ex, "/?error=" + encode("Account not found."));
            return;
        }

        if (account.pin == pin) {
            failedAttempts.remove(accountNumber);
            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, accountNumber);
            ex.getResponseHeaders().add("Set-Cookie", "session=" + sessionId + "; Path=/; HttpOnly");
            redirect(ex, "/dashboard");
        } else {
            int attempts = failedAttempts.merge(accountNumber, 1, Integer::sum);
            if (attempts >= MAX_ATTEMPTS) {
                lockedAccounts.add(accountNumber);
                redirect(ex, "/?error=" + encode("Incorrect PIN. Account is now locked."));
            } else {
                int remaining = MAX_ATTEMPTS - attempts;
                redirect(ex, "/?error=" + encode("Incorrect PIN. Attempts remaining: " + remaining));
            }
        }
    }

    static void handleDashboard(HttpExchange ex) throws IOException {
        Long accNo = requireLogin(ex);
        if (accNo == null) {
            return;
        }

        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        String message = q.get("message");
        String error = q.get("error");

        Account account = repo.findByAccountNumber(accNo);
        if (account == null) {
            redirect(ex, "/logout");
            return;
        }

        List<Account> ownAccounts = repo.findByOwnerId(account.ownerId);
        sendHtml(ex, 200, dashboardHtml(account, ownAccounts, message, error));
    }

    static void handleDeposit(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            notFound(ex);
            return;
        }
        Long accNo = requireLogin(ex);
        if (accNo == null) {
            return;
        }

        Map<String, String> form = parseForm(ex);
        Account account = repo.findByAccountNumber(accNo);
        if (account == null) {
            redirect(ex, "/logout");
            return;
        }

        try {
            double amount = Double.parseDouble(form.getOrDefault("amount", ""));
            if (amount <= 0) {
                redirect(ex, "/dashboard?error=" + encode("Deposit amount must be positive."));
                return;
            }
            account.balance += amount;
            repo.update(account);
            redirect(ex, "/dashboard?message=" + encode("Deposit successful. New balance: $" + fmt(account.balance)));
        } catch (NumberFormatException e) {
            redirect(ex, "/dashboard?error=" + encode("Invalid deposit amount."));
        }
    }

    static void handleWithdraw(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            notFound(ex);
            return;
        }
        Long accNo = requireLogin(ex);
        if (accNo == null) {
            return;
        }

        Map<String, String> form = parseForm(ex);
        Account account = repo.findByAccountNumber(accNo);
        if (account == null) {
            redirect(ex, "/logout");
            return;
        }

        try {
            double amount = Double.parseDouble(form.getOrDefault("amount", ""));
            if (amount <= 0) {
                redirect(ex, "/dashboard?error=" + encode("Withdrawal amount must be positive."));
                return;
            }
            if (amount > account.balance) {
                redirect(ex, "/dashboard?error=" + encode("Insufficient balance."));
                return;
            }
            account.balance -= amount;
            repo.update(account);
            redirect(ex, "/dashboard?message=" + encode("Withdrawal successful. New balance: $" + fmt(account.balance)));
        } catch (NumberFormatException e) {
            redirect(ex, "/dashboard?error=" + encode("Invalid withdrawal amount."));
        }
    }

    static void handleDetails(HttpExchange ex) throws IOException {
        Long accNo = requireLogin(ex);
        if (accNo == null) {
            return;
        }

        Account account = repo.findByAccountNumber(accNo);
        if (account == null) {
            redirect(ex, "/logout");
            return;
        }

        sendHtml(ex, 200, detailsHtml(account));
    }

    static void handleLogout(HttpExchange ex) throws IOException {
        String sessionId = getCookie(ex, "session");
        if (sessionId != null) {
            sessions.remove(sessionId);
        }
        ex.getResponseHeaders().add("Set-Cookie", "session=; Path=/; Max-Age=0");
        redirect(ex, "/");
    }

    // ---------- Transfers (external transfer + self-transfer between a person's own accounts) ----------
    static void handleTransfer(HttpExchange ex) throws IOException {
        Long accNo = requireLogin(ex);
        if (accNo == null) {
            return;
        }

        Account account = repo.findByAccountNumber(accNo);
        if (account == null) {
            redirect(ex, "/logout");
            return;
        }

        if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            List<Account> ownAccounts = repo.findByOwnerId(account.ownerId);
            sendHtml(ex, 200, transferHtml(account, ownAccounts, q.get("error"), q.get("message")));
            return;
        }

        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            notFound(ex);
            return;
        }

        Map<String, String> form = parseForm(ex);

        long toAccountNumber;
        try {
            toAccountNumber = Long.parseLong(form.getOrDefault("toAccountNumber", "").trim());
        } catch (NumberFormatException e) {
            redirect(ex, "/transfer?error=" + encode("Please enter a valid destination account number."));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(form.getOrDefault("amount", ""));
        } catch (NumberFormatException e) {
            redirect(ex, "/transfer?error=" + encode("Please enter a valid transfer amount."));
            return;
        }

        String note = form.getOrDefault("note", "").trim().replace("|", "/").replaceAll("[\\r\\n]+", " ");

        AccountRepository.TransferResult result = repo.transfer(accNo, toAccountNumber, amount);
        if (!result.ok) {
            redirect(ex, "/transfer?error=" + encode(result.reason));
            return;
        }

        boolean isSelf = result.to.ownerId.equals(account.ownerId);
        long id = transferRepo.nextId();
        transferRepo.insert(new Transfer(id, accNo, toAccountNumber, amount, note,
                LocalDate.now().toString(), isSelf ? "SELF" : "EXTERNAL"));

        String kind = isSelf ? "Self-transfer" : "Transfer";
        redirect(ex, "/dashboard?message=" + encode(kind + " of $" + fmt(amount) + " to account #" + toAccountNumber
                + " complete. Your new balance: $" + fmt(result.from.balance)));
    }

    static void handleTransferHistory(HttpExchange ex) throws IOException {
        Long accNo = requireLogin(ex);
        if (accNo == null) {
            return;
        }
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            notFound(ex);
            return;
        }

        List<Transfer> transfers = transferRepo.findByAccountNumber(accNo);
        sendHtml(ex, 200, transferHistoryHtml(accNo, transfers));
    }

    // ---------- Loan application (customer side) ----------
    static void handleLoanApply(HttpExchange ex) throws IOException {
        Long accNo = requireLogin(ex);
        if (accNo == null) {
            return;
        }

        if (ex.getRequestMethod().equalsIgnoreCase("GET")) {
            Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
            sendHtml(ex, 200, loanApplyHtml(q.get("error")));
            return;
        }

        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            notFound(ex);
            return;
        }

        Account account = repo.findByAccountNumber(accNo);
        if (account == null) {
            redirect(ex, "/logout");
            return;
        }

        String contentType = ex.getRequestHeaders().getFirst("Content-Type");
        if (contentType == null || !contentType.toLowerCase().startsWith("multipart/form-data")) {
            redirect(ex, "/loan/apply?error=" + encode("Please use the application form (documents are required)."));
            return;
        }

        String boundary = extractBoundary(contentType);
        if (boundary == null) {
            redirect(ex, "/loan/apply?error=" + encode("Malformed upload. Please try again."));
            return;
        }

        MultipartData form;
        try {
            form = parseMultipart(ex.getRequestBody(), boundary);
        } catch (IOException e) {
            redirect(ex, "/loan/apply?error=" + encode("Could not read the uploaded files. Please try again."));
            return;
        }

        String purpose = form.fields.getOrDefault("purpose", "").trim().replace("|", "/").replaceAll("[\\r\\n]+", " ");
        if (purpose.isBlank()) {
            purpose = "Not specified";
        }

        double amount;
        try {
            amount = Double.parseDouble(form.fields.getOrDefault("amount", ""));
        } catch (NumberFormatException e) {
            redirect(ex, "/loan/apply?error=" + encode("Please enter a valid loan amount."));
            return;
        }
        if (amount <= 0) {
            redirect(ex, "/loan/apply?error=" + encode("Loan amount must be positive."));
            return;
        }

        String documentType = form.fields.getOrDefault("documentType", "bank").trim();
        if (!documentType.equals("bank") && !documentType.equals("salary")) {
            documentType = "bank";
        }

        UploadedFile document = form.files.get("document");
        if (document == null || document.data.length == 0) {
            redirect(ex, "/loan/apply?error=" + encode("Please upload a document."));
            return;
        }

        String docExt = fileExtension(document.fileName);
        if (!ALLOWED_DOC_EXTENSIONS.contains(docExt)) {
            redirect(ex, "/loan/apply?error=" + encode("Document must be a PDF, DOC/DOCX, or image (JPG/PNG)."));
            return;
        }
        if (document.data.length > MAX_UPLOAD_BYTES) {
            redirect(ex, "/loan/apply?error=" + encode("File must be 5 MB or smaller."));
            return;
        }

        long id = loanRepo.nextId();

        String bankFileName = "";
        String salaryFileName = "";
        try {
            Files.createDirectories(Paths.get(UPLOAD_DIR));
            if (documentType.equals("salary")) {
                salaryFileName = "loan_" + id + "_salaryslip." + docExt;
                Files.write(Paths.get(UPLOAD_DIR, salaryFileName), document.data);
            } else {
                bankFileName = "loan_" + id + "_bankstatement." + docExt;
                Files.write(Paths.get(UPLOAD_DIR, bankFileName), document.data);
            }
        } catch (IOException e) {
            redirect(ex, "/loan/apply?error=" + encode("Could not save the uploaded document. Please try again."));
            return;
        }

        Loan loan = new Loan(id, accNo, amount, purpose, "PENDING", LocalDate.now().toString(), "",
                bankFileName, salaryFileName);
        loanRepo.insert(loan);

        redirect(ex, "/dashboard?message=" + encode("Loan application #" + id + " submitted for $" + fmt(amount) + " with your document. Awaiting employee approval."));
    }

    static void handleLoanDocument(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            notFound(ex);
            return;
        }

        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        long loanId;
        try {
            loanId = Long.parseLong(q.getOrDefault("loanId", ""));
        } catch (NumberFormatException e) {
            notFound(ex);
            return;
        }
        String type = q.getOrDefault("type", "");

        Loan loan = loanRepo.findById(loanId);
        if (loan == null) {
            notFound(ex);
            return;
        }

        // Only the applicant themselves, or a logged-in employee, may view the documents.
        Long custAccNo = currentAccount(ex);
        String empUser = currentEmployee(ex);
        boolean isOwner = custAccNo != null && custAccNo == loan.accountNumber;
        boolean isEmployee = empUser != null;
        if (!isOwner && !isEmployee) {
            redirect(ex, "/?error=" + encode("Please log in to view loan documents."));
            return;
        }

        String fileName = switch (type) {
            case "bank" ->
                loan.bankStatementFile;
            case "salary" ->
                loan.salarySlipFile;
            default ->
                null;
        };
        if (fileName == null || fileName.isBlank()) {
            notFound(ex);
            return;
        }

        Path filePath = Paths.get(UPLOAD_DIR, fileName);
        if (!Files.exists(filePath)) {
            notFound(ex);
            return;
        }

        byte[] fileBytes = Files.readAllBytes(filePath);
        String ext = fileExtension(fileName);
        String mime = switch (ext) {
            case "pdf" ->
                "application/pdf";
            case "jpg", "jpeg" ->
                "image/jpeg";
            case "png" ->
                "image/png";
            case "doc" ->
                "application/msword";
            case "docx" ->
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
            default ->
                "application/octet-stream";
        };
        boolean viewableInline = ext.equals("pdf") || ext.equals("jpg") || ext.equals("jpeg") || ext.equals("png");

        ex.getResponseHeaders().add("Content-Type", mime);
        ex.getResponseHeaders().add("Content-Disposition",
                (viewableInline ? "inline" : "attachment") + "; filename=\"" + fileName + "\"");
        ex.sendResponseHeaders(200, fileBytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(fileBytes);
        }
    }

    static void handleLoanStatus(HttpExchange ex) throws IOException {
        Long accNo = requireLogin(ex);
        if (accNo == null) {
            return;
        }
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            notFound(ex);
            return;
        }

        List<Loan> loans = loanRepo.findByAccountNumber(accNo);
        sendHtml(ex, 200, loanStatusHtml(loans));
    }

    // ---------- Employee portal (approve / reject loans) ----------
    static void handleEmployeeLoginPage(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            notFound(ex);
            return;
        }

        String empUser = currentEmployee(ex);
        if (empUser != null) {
            redirect(ex, "/employee/dashboard");
            return;
        }

        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        sendHtml(ex, 200, employeeLoginHtml(q.get("error")));
    }

    static void handleEmployeeLogin(HttpExchange ex) throws IOException {
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            notFound(ex);
            return;
        }

        Map<String, String> form = parseForm(ex);
        String username = form.getOrDefault("username", "").trim();
        String password = form.getOrDefault("password", "");

        Employee emp = employeeRepo.findByUsername(username);
        if (emp == null || !emp.password.equals(password)) {
            redirect(ex, "/employee?error=" + encode("Invalid employee username or password."));
            return;
        }

        String sessionId = UUID.randomUUID().toString();
        employeeSessions.put(sessionId, emp.username);
        ex.getResponseHeaders().add("Set-Cookie", "empsession=" + sessionId + "; Path=/; HttpOnly");
        redirect(ex, "/employee/dashboard");
    }

    static void handleEmployeeDashboard(HttpExchange ex) throws IOException {
        String empUser = requireEmployeeLogin(ex);
        if (empUser == null) {
            return;
        }
        if (!ex.getRequestMethod().equalsIgnoreCase("GET")) {
            notFound(ex);
            return;
        }

        Map<String, String> q = parseQuery(ex.getRequestURI().getRawQuery());
        List<Loan> pending = loanRepo.findByStatus("PENDING");
        List<Loan> decided = new ArrayList<>();
        decided.addAll(loanRepo.findByStatus("APPROVED"));
        decided.addAll(loanRepo.findByStatus("REJECTED"));

        Employee emp = employeeRepo.findByUsername(empUser);
        String empName = (emp != null) ? emp.name : empUser;

        sendHtml(ex, 200, employeeDashboardHtml(empName, pending, decided, q.get("message"), q.get("error")));
    }

    static void handleLoanApprove(HttpExchange ex) throws IOException {
        handleLoanDecision(ex, "APPROVED");
    }

    static void handleLoanReject(HttpExchange ex) throws IOException {
        handleLoanDecision(ex, "REJECTED");
    }

    static void handleLoanDecision(HttpExchange ex, String decision) throws IOException {
        String empUser = requireEmployeeLogin(ex);
        if (empUser == null) {
            return;
        }
        if (!ex.getRequestMethod().equalsIgnoreCase("POST")) {
            notFound(ex);
            return;
        }

        Map<String, String> form = parseForm(ex);
        long loanId;
        try {
            loanId = Long.parseLong(form.getOrDefault("loanId", ""));
        } catch (NumberFormatException e) {
            redirect(ex, "/employee/dashboard?error=" + encode("Invalid loan id."));
            return;
        }

        Loan loan = loanRepo.findById(loanId);
        if (loan == null) {
            redirect(ex, "/employee/dashboard?error=" + encode("Loan application not found."));
            return;
        }
        if (!loan.status.equals("PENDING")) {
            redirect(ex, "/employee/dashboard?error=" + encode("Loan #" + loanId + " was already decided."));
            return;
        }

        if (decision.equals("APPROVED")) {
            Account account = repo.findByAccountNumber(loan.accountNumber);
            if (account == null) {
                redirect(ex, "/employee/dashboard?error=" + encode("Applicant account not found."));
                return;
            }
            account.balance += loan.amount;
            repo.update(account);
        }

        loan.status = decision;
        loan.decidedBy = empUser;
        loanRepo.update(loan);

        redirect(ex, "/employee/dashboard?message=" + encode("Loan #" + loanId + " " + decision.toLowerCase() + "."));
    }

    static void handleEmployeeLogout(HttpExchange ex) throws IOException {
        String sessionId = getCookie(ex, "empsession");
        if (sessionId != null) {
            employeeSessions.remove(sessionId);
        }
        ex.getResponseHeaders().add("Set-Cookie", "empsession=; Path=/; Max-Age=0");
        redirect(ex, "/employee");
    }

    static Long currentAccount(HttpExchange ex) {
        String sessionId = getCookie(ex, "session");
        if (sessionId == null) {
            return null;
        }
        return sessions.get(sessionId);
    }

    static Long requireLogin(HttpExchange ex) throws IOException {
        Long accNo = currentAccount(ex);
        if (accNo == null) {
            redirect(ex, "/?error=" + encode("Please log in first."));
            return null;
        }
        return accNo;
    }

    static String currentEmployee(HttpExchange ex) {
        String sessionId = getCookie(ex, "empsession");
        if (sessionId == null) {
            return null;
        }
        return employeeSessions.get(sessionId);
    }

    static String requireEmployeeLogin(HttpExchange ex) throws IOException {
        String empUser = currentEmployee(ex);
        if (empUser == null) {
            redirect(ex, "/employee?error=" + encode("Please log in as an employee first."));
            return null;
        }
        return empUser;
    }

    static String getCookie(HttpExchange ex, String name) {
        List<String> cookieHeaders = ex.getRequestHeaders().get("Cookie");
        if (cookieHeaders == null) {
            return null;
        }
        for (String header : cookieHeaders) {
            for (String part : header.split(";")) {
                String[] kv = part.trim().split("=", 2);
                if (kv.length == 2 && kv[0].equals(name)) {
                    return kv[1];
                }
            }
        }
        return null;
    }

    static Map<String, String> parseForm(HttpExchange ex) throws IOException {
        String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return parseQuery(body);
    }

    // ---------- multipart/form-data parsing (for the document-upload loan form) ----------
    static String extractBoundary(String contentType) {
        for (String part : contentType.split(";")) {
            part = part.trim();
            if (part.toLowerCase().startsWith("boundary=")) {
                String b = part.substring("boundary=".length()).trim();
                if (b.startsWith("\"") && b.endsWith("\"") && b.length() >= 2) {
                    b = b.substring(1, b.length() - 1);
                }
                return b;
            }
        }
        return null;
    }

    static MultipartData parseMultipart(InputStream in, String boundary) throws IOException {
        byte[] data = in.readAllBytes();
        byte[] delimiter = ("--" + boundary).getBytes(StandardCharsets.UTF_8);
        byte[] headerBodySep = "\r\n\r\n".getBytes(StandardCharsets.UTF_8);

        MultipartData result = new MultipartData();

        List<Integer> positions = new ArrayList<>();
        int searchFrom = 0;
        while (true) {
            int pos = indexOf(data, delimiter, searchFrom);
            if (pos == -1) {
                break;
            }
            positions.add(pos);
            searchFrom = pos + delimiter.length;
        }

        for (int i = 0; i < positions.size() - 1; i++) {
            int partStart = positions.get(i) + delimiter.length;
            int partEnd = positions.get(i + 1);

            // The terminating boundary is followed by "--"; nothing useful comes after it.
            if (partStart + 1 < data.length && data[partStart] == '-' && data[partStart + 1] == '-') {
                continue;
            }
            if (partStart + 1 < data.length && data[partStart] == '\r' && data[partStart + 1] == '\n') {
                partStart += 2;
            }
            int contentEnd = partEnd;
            if (contentEnd - 2 >= partStart && data[contentEnd - 2] == '\r' && data[contentEnd - 1] == '\n') {
                contentEnd -= 2;
            }
            if (contentEnd <= partStart) {
                continue;
            }

            int sep = indexOf(data, headerBodySep, partStart);
            if (sep == -1 || sep > contentEnd) {
                continue;
            }

            String headerText = new String(data, partStart, sep - partStart, StandardCharsets.UTF_8);
            int contentStart = sep + headerBodySep.length;
            byte[] content = Arrays.copyOfRange(data, contentStart, Math.max(contentStart, contentEnd));

            String name = null, fileName = null, partContentType = null;
            for (String line : headerText.split("\r\n")) {
                String lower = line.toLowerCase();
                if (lower.startsWith("content-disposition")) {
                    for (String piece : line.split(";")) {
                        piece = piece.trim();
                        if (piece.toLowerCase().startsWith("name=")) {
                            name = stripQuotes(piece.substring(5));
                        } else if (piece.toLowerCase().startsWith("filename=")) {
                            fileName = stripQuotes(piece.substring(9));
                        }
                    }
                } else if (lower.startsWith("content-type")) {
                    partContentType = line.substring(line.indexOf(":") + 1).trim();
                }
            }
            if (name == null) {
                continue;
            }

            if (fileName != null) {
                result.files.put(name, new UploadedFile(fileName, partContentType, content));
            } else {
                result.fields.put(name, new String(content, StandardCharsets.UTF_8));
            }
        }

        return result;
    }

    static int indexOf(byte[] data, byte[] pattern, int from) {
        outer:
        for (int i = Math.max(from, 0); i <= data.length - pattern.length; i++) {
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    static String stripQuotes(String s) {
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    static String fileExtension(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot == -1 || dot == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dot + 1).toLowerCase();
    }

    static class UploadedFile {

        final String fileName;
        final String contentType;
        final byte[] data;

        UploadedFile(String fileName, String contentType, byte[] data) {
            this.fileName = fileName;
            this.contentType = contentType;
            this.data = data;
        }
    }

    static class MultipartData {

        final Map<String, String> fields = new HashMap<>();
        final Map<String, UploadedFile> files = new HashMap<>();
    }

    static Map<String, String> parseQuery(String query) {
        Map<String, String> map = new HashMap<>();
        if (query == null || query.isBlank()) {
            return map;
        }
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            String key = URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String value = kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, value);
        }
        return map;
    }

    static String encode(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    static void redirect(HttpExchange ex, String location) throws IOException {
        ex.getResponseHeaders().add("Location", location);
        ex.sendResponseHeaders(302, -1);
        ex.close();
    }

    static void notFound(HttpExchange ex) throws IOException {
        sendHtml(ex, 404, "<h1>404 Not Found</h1>");
    }

    static void sendHtml(HttpExchange ex, int status, String html) throws IOException {
        byte[] bytes = html.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    static String fmt(double amount) {
        return String.format("%.2f", amount);
    }

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    static final String STYLE = """
        <style>
            body { font-family: -apple-system, Segoe UI, Roboto, Arial, sans-serif;
                   background: #0f172a; color: #e2e8f0; display: flex;
                   justify-content: center; align-items: center; min-height: 100vh; margin:0; }
            .card { background: #1e293b; padding: 32px 36px; border-radius: 12px;
                    box-shadow: 0 10px 30px rgba(0,0,0,0.4); width: 360px; }
            h1 { font-size: 22px; margin-top: 0; color: #f8fafc; }
            label { display:block; margin-top: 14px; font-size: 13px; color:#94a3b8; }
            input { width: 100%; padding: 10px; margin-top: 6px; border-radius: 6px;
                    border: 1px solid #334155; background: #0f172a; color: #f8fafc; box-sizing: border-box; }
            button { margin-top: 20px; width: 100%; padding: 11px; border: none; border-radius: 6px;
                      background: #6366f1; color: white; font-weight: 600; cursor: pointer; font-size: 14px; }
            button:hover { background: #4f46e5; }
            button.secondary { background: #334155; }
            button.secondary:hover { background: #475569; }
            .error { background: #7f1d1d; color: #fecaca; padding: 10px 12px; border-radius: 6px; font-size: 13px; margin-top: 14px; }
            .message { background: #14532d; color: #bbf7d0; padding: 10px 12px; border-radius: 6px; font-size: 13px; margin-top: 14px; }
            .balance { font-size: 32px; font-weight: 700; color: #4ade80; margin: 4px 0 18px 0; }
            .row { display: flex; gap: 10px; }
            .row form { flex: 1; }
            a.link { color: #a5b4fc; font-size: 13px; text-decoration:none; }
            .detail-line { display:flex; justify-content:space-between; padding: 8px 0; border-bottom: 1px solid #334155; font-size: 14px; }
            .card.wide { width: 860px; }
            table.loans { width: 100%; border-collapse: collapse; margin-top: 12px; font-size: 13px; }
            table.loans th, table.loans td { text-align: left; padding: 8px 10px; border-bottom: 1px solid #334155; }
            table.loans th { color: #94a3b8; font-weight: 600; }
            .badge { padding: 3px 9px; border-radius: 999px; font-size: 12px; font-weight: 600; }
            .badge.PENDING { background: #78350f; color: #fde68a; }
            .badge.APPROVED { background: #14532d; color: #bbf7d0; }
            .badge.REJECTED { background: #7f1d1d; color: #fecaca; }
            form.inline { display: inline; }
            button.small { width: auto; margin-top: 0; padding: 6px 12px; font-size: 12px; }
            button.approve { background: #16a34a; }
            button.approve:hover { background: #15803d; }
            button.reject { background: #dc2626; }
            button.reject:hover { background: #b91c1c; }
            .section-title { margin-top: 26px; margin-bottom: 4px; font-size: 15px; color: #f8fafc; }
            .muted { color: #94a3b8; font-size: 13px; }
        </style>
        """;

    static String loginPageHtml(String error) {
        String errorHtml = (error != null) ? "<div class='error'>" + escape(error) + "</div>" : "";
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>Bank Login</title>%s</head>
            <body>
              <div class="card">
                <h1>🏦 Online Banking Login</h1>
                <form action="/login" method="POST">
                  <label>Account Number</label>
                  <input type="text" name="accountNumber" required autofocus>
                  <label>PIN</label>
                  <input type="password" name="pin" required>
                  <button type="submit">Log In</button>
                </form>
                %s
                <div style="margin-top:16px; text-align:center;">
                  <a class="link" href="/employee">Bank Employee? Log in to the employee portal &rarr;</a>
                </div>
              </div>
            </body></html>
            """.formatted(STYLE, errorHtml);
    }

    static String dashboardHtml(Account account, List<Account> ownAccounts, String message, String error) {
        String msgHtml = (message != null) ? "<div class='message'>" + escape(message) + "</div>" : "";
        String errHtml = (error != null) ? "<div class='error'>" + escape(error) + "</div>" : "";

        List<Account> siblings = new ArrayList<>();
        for (Account a : ownAccounts) {
            if (a.accountNumber != account.accountNumber) {
                siblings.add(a);
            }
        }
        String otherAccountsHtml = "";
        if (!siblings.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("<div style=\"margin-top:16px; font-size:13px; color:#94a3b8;\">Your other accounts:</div>");
            for (Account a : siblings) {
                sb.append("<div class='detail-line'><span>#").append(a.accountNumber).append(" (")
                        .append(escape(a.label)).append(")</span><span>$").append(fmt(a.balance)).append("</span></div>");
            }
            otherAccountsHtml = sb.toString();
        }

        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>Dashboard</title>%s</head>
            <body>
              <div class="card">
                <h1>Welcome, %s</h1>
                <div style="font-size:13px;color:#94a3b8;">Account #%d &middot; %s</div>
                <div class="balance">$%s</div>

                <div class="row">
                  <form action="/deposit" method="POST">
                    <label>Deposit</label>
                    <input type="number" step="0.01" min="0.01" name="amount" placeholder="$ amount" required>
                    <button type="submit">Deposit</button>
                  </form>
                  <form action="/withdraw" method="POST">
                    <label>Withdraw</label>
                    <input type="number" step="0.01" min="0.01" name="amount" placeholder="$ amount" required>
                    <button type="submit">Withdraw</button>
                  </form>
                </div>

                %s
                %s
                %s

                <div class="row" style="margin-top:16px;">
                  <form action="/transfer" method="GET">
                    <button type="submit">Transfer Money</button>
                  </form>
                  <form action="/transfer/history" method="GET">
                    <button type="submit" class="secondary">Transfer History</button>
                  </form>
                </div>

                <div class="row" style="margin-top:10px;">
                  <form action="/loan/apply" method="GET">
                    <button type="submit" class="secondary">Apply for a Loan</button>
                  </form>
                  <form action="/loan/status" method="GET">
                    <button type="submit" class="secondary">My Loan Applications</button>
                  </form>
                </div>

                <form action="/details" method="GET" style="margin-top:10px;">
                  <button type="submit" class="secondary">View Account Details</button>
                </form>
                <form action="/logout" method="GET">
                  <button type="submit" class="secondary">Log Out</button>
                </form>
              </div>
            </body></html>
            """.formatted(STYLE, escape(account.name), account.accountNumber, escape(account.label),
                fmt(account.balance), msgHtml, errHtml, otherAccountsHtml);
    }

    static String detailsHtml(Account account) {
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>Account Details</title>%s</head>
            <body>
              <div class="card">
                <h1>Account Details</h1>
                <div class="detail-line"><span>Account Number</span><span>%d</span></div>
                <div class="detail-line"><span>Account Holder</span><span>%s</span></div>
                <div class="detail-line"><span>Account Type</span><span>%s</span></div>
                <div class="detail-line"><span>Balance</span><span>$%s</span></div>
                <form action="/dashboard" method="GET" style="margin-top:20px;">
                  <button type="submit" class="secondary">Back to Dashboard</button>
                </form>
              </div>
            </body></html>
            """.formatted(STYLE, account.accountNumber, escape(account.name), escape(account.label), fmt(account.balance));
    }

    static String transferHtml(Account account, List<Account> ownAccounts, String error, String message) {
        String errHtml = (error != null) ? "<div class='error'>" + escape(error) + "</div>" : "";
        String msgHtml = (message != null) ? "<div class='message'>" + escape(message) + "</div>" : "";

        StringBuilder ownAccountsHtml = new StringBuilder();
        List<Account> siblings = new ArrayList<>();
        for (Account a : ownAccounts) {
            if (a.accountNumber != account.accountNumber) {
                siblings.add(a);
            }
        }
        if (!siblings.isEmpty()) {
            ownAccountsHtml.append("<div class='section-title'>Your Other Accounts (self-transfer)</div>");
            ownAccountsHtml.append("<table class='loans'><tr><th>Account</th><th>Type</th><th>Balance</th><th></th></tr>");
            for (Account a : siblings) {
                ownAccountsHtml.append("<tr>")
                        .append("<td>#").append(a.accountNumber).append("</td>")
                        .append("<td>").append(escape(a.label)).append("</td>")
                        .append("<td>$").append(fmt(a.balance)).append("</td>")
                        .append("<td><button type='button' class='small secondary' onclick=\"document.getElementById('toAccountNumber').value='")
                        .append(a.accountNumber).append("'\">Use</button></td>")
                        .append("</tr>");
            }
            ownAccountsHtml.append("</table>");
        }

        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>Transfer Money</title>%s</head>
            <body>
              <div class="card wide">
                <h1>Transfer Money</h1>
                <div class="muted">From account #%d (%s) &mdash; balance $%s</div>
                %s
                %s

                <form action="/transfer" method="POST" style="margin-top:16px; max-width:360px;">
                  <label>To Account Number</label>
                  <input type="text" id="toAccountNumber" name="toAccountNumber" placeholder="e.g. %d" required>
                  <label>Amount ($)</label>
                  <input type="number" step="0.01" min="0.01" name="amount" placeholder="$ amount" required>
                  <label>Note (optional)</label>
                  <input type="text" name="note" placeholder="e.g. Rent, savings top-up">
                  <button type="submit">Send Transfer</button>
                </form>

                %s

                <div class="row" style="margin-top:20px;">
                  <form action="/transfer/history" method="GET">
                    <button type="submit" class="secondary">Transfer History</button>
                  </form>
                  <form action="/dashboard" method="GET">
                    <button type="submit" class="secondary">Back to Dashboard</button>
                  </form>
                </div>
              </div>
            </body></html>
            """.formatted(STYLE, account.accountNumber, escape(account.label), fmt(account.balance),
                msgHtml, errHtml, account.accountNumber, ownAccountsHtml.toString());
    }

    static String transferHistoryHtml(long accNo, List<Transfer> transfers) {
        StringBuilder rows = new StringBuilder();
        if (transfers.isEmpty()) {
            rows.append("<tr><td colspan='6' class='muted'>No transfers yet.</td></tr>");
        } else {
            for (Transfer t : transfers) {
                boolean outgoing = t.fromAccount == accNo;
                String direction = outgoing ? "Sent to #" + t.toAccount : "Received from #" + t.fromAccount;
                String amountHtml = (outgoing ? "-$" : "+$") + fmt(t.amount);
                String typeLabel = t.type.equals("SELF") ? "Self-transfer" : "Transfer";
                rows.append("<tr>")
                        .append("<td>#").append(t.id).append("</td>")
                        .append("<td>").append(escape(direction)).append("</td>")
                        .append("<td>").append(amountHtml).append("</td>")
                        .append("<td>").append(escape(typeLabel)).append("</td>")
                        .append("<td>").append(escape(t.date)).append("</td>")
                        .append("<td>").append(t.note.isBlank() ? "<span class='muted'>&mdash;</span>" : escape(t.note)).append("</td>")
                        .append("</tr>");
            }
        }

        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>Transfer History</title>%s</head>
            <body>
              <div class="card wide">
                <h1>Transfer History</h1>
                <table class="loans">
                  <tr><th>ID</th><th>Direction</th><th>Amount</th><th>Type</th><th>Date</th><th>Note</th></tr>
                  %s
                </table>
                <div class="row" style="margin-top:20px;">
                  <form action="/transfer" method="GET">
                    <button type="submit">Make a Transfer</button>
                  </form>
                  <form action="/dashboard" method="GET">
                    <button type="submit" class="secondary">Back to Dashboard</button>
                  </form>
                </div>
              </div>
            </body></html>
            """.formatted(STYLE, rows.toString());
    }

    static String loanApplyHtml(String error) {
        String errHtml = (error != null) ? "<div class='error'>" + escape(error) + "</div>" : "";
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>Apply for a Loan</title>%s</head>
            <body>
              <div class="card">
                <h1>Apply for a Loan</h1>
                <form action="/loan/apply" method="POST" enctype="multipart/form-data">
                  <label>Loan Amount ($)</label>
                  <input type="number" step="0.01" min="0.01" name="amount" placeholder="e.g. 5000" required>
                  <label>Purpose</label>
                  <input type="text" name="purpose" placeholder="e.g. Home renovation" required>
                  <label>Supporting Document</label>
                  <div style="display:flex; gap:16px; margin-top:6px; font-size:13px; color:#e2e8f0;">
                    <label style="display:inline-flex; align-items:center; gap:6px; margin-top:0;">
                      <input type="radio" name="documentType" value="bank" checked style="width:auto; margin-top:0;"> Bank Statement
                    </label>
                    <label style="display:inline-flex; align-items:center; gap:6px; margin-top:0;">
                      <input type="radio" name="documentType" value="salary" style="width:auto; margin-top:0;"> Salary Slip
                    </label>
                  </div>
                  <label>Upload Document (PDF, DOC, or image, max 5 MB)</label>
                  <input type="file" name="document" accept=".pdf,.doc,.docx,.jpg,.jpeg,.png" required>
                  <button type="submit">Submit Application</button>
                </form>
                %s
                <form action="/dashboard" method="GET" style="margin-top:16px;">
                  <button type="submit" class="secondary">Back to Dashboard</button>
                </form>
              </div>
            </body></html>
            """.formatted(STYLE, errHtml);
    }

    static String loanStatusHtml(List<Loan> loans) {
        StringBuilder rows = new StringBuilder();
        if (loans.isEmpty()) {
            rows.append("<tr><td colspan='6' class='muted'>No loan applications yet.</td></tr>");
        } else {
            for (Loan loan : loans) {
                String docsHtml = loanDocumentLinks(loan);
                rows.append("<tr>")
                        .append("<td>#").append(loan.id).append("</td>")
                        .append("<td>$").append(fmt(loan.amount)).append("</td>")
                        .append("<td>").append(escape(loan.purpose)).append("</td>")
                        .append("<td>").append(escape(loan.appliedDate)).append("</td>")
                        .append("<td><span class='badge ").append(loan.status).append("'>").append(loan.status).append("</span></td>")
                        .append("<td>").append(docsHtml).append("</td>")
                        .append("</tr>");
            }
        }

        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>My Loan Applications</title>%s</head>
            <body>
              <div class="card wide">
                <h1>My Loan Applications</h1>
                <table class="loans">
                  <tr><th>ID</th><th>Amount</th><th>Purpose</th><th>Applied</th><th>Status</th><th>Documents</th></tr>
                  %s
                </table>
                <form action="/loan/apply" method="GET" style="margin-top:20px;">
                  <button type="submit">Apply for Another Loan</button>
                </form>
                <form action="/dashboard" method="GET" style="margin-top:10px;">
                  <button type="submit" class="secondary">Back to Dashboard</button>
                </form>
              </div>
            </body></html>
            """.formatted(STYLE, rows.toString());
    }

    static String loanDocumentLinks(Loan loan) {
        List<String> links = new ArrayList<>();
        if (loan.bankStatementFile != null && !loan.bankStatementFile.isBlank()) {
            links.add("<a class='link' target='_blank' href='/loan/document?loanId=" + loan.id + "&type=bank'>Bank Statement</a>");
        }
        if (loan.salarySlipFile != null && !loan.salarySlipFile.isBlank()) {
            links.add("<a class='link' target='_blank' href='/loan/document?loanId=" + loan.id + "&type=salary'>Salary Slip</a>");
        }
        return links.isEmpty() ? "<span class='muted'>&mdash;</span>" : String.join("<br>", links);
    }

    static String employeeLoginHtml(String error) {
        String errorHtml = (error != null) ? "<div class='error'>" + escape(error) + "</div>" : "";
        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>Employee Login</title>%s</head>
            <body>
              <div class="card">
                <h1>🧑‍💼 Employee Portal Login</h1>
                <form action="/employee/login" method="POST">
                  <label>Username</label>
                  <input type="text" name="username" required autofocus>
                  <label>Password</label>
                  <input type="password" name="password" required>
                  <button type="submit">Log In</button>
                </form>
                %s
                <div style="margin-top:16px; text-align:center;">
                  <a class="link" href="/">&larr; Back to customer login</a>
                </div>
              </div>
            </body></html>
            """.formatted(STYLE, errorHtml);
    }

    static String employeeDashboardHtml(String empName, List<Loan> pending, List<Loan> decided, String message, String error) {
        String msgHtml = (message != null) ? "<div class='message'>" + escape(message) + "</div>" : "";
        String errHtml = (error != null) ? "<div class='error'>" + escape(error) + "</div>" : "";

        StringBuilder pendingRows = new StringBuilder();
        if (pending.isEmpty()) {
            pendingRows.append("<tr><td colspan='7' class='muted'>No pending loan applications.</td></tr>");
        } else {
            for (Loan loan : pending) {
                Account applicant = repo.findByAccountNumber(loan.accountNumber);
                String applicantName = (applicant != null) ? applicant.name : "Unknown";
                pendingRows.append("<tr>")
                        .append("<td>#").append(loan.id).append("</td>")
                        .append("<td>").append(loan.accountNumber).append(" - ").append(escape(applicantName)).append("</td>")
                        .append("<td>$").append(fmt(loan.amount)).append("</td>")
                        .append("<td>").append(escape(loan.purpose)).append("</td>")
                        .append("<td>").append(escape(loan.appliedDate)).append("</td>")
                        .append("<td>").append(loanDocumentLinks(loan)).append("</td>")
                        .append("<td>")
                        .append("<form class='inline' action='/employee/loan/approve' method='POST'>")
                        .append("<input type='hidden' name='loanId' value='").append(loan.id).append("'>")
                        .append("<button type='submit' class='small approve'>Approve</button></form> ")
                        .append("<form class='inline' action='/employee/loan/reject' method='POST'>")
                        .append("<input type='hidden' name='loanId' value='").append(loan.id).append("'>")
                        .append("<button type='submit' class='small reject'>Reject</button></form>")
                        .append("</td>")
                        .append("</tr>");
            }
        }

        StringBuilder decidedRows = new StringBuilder();
        if (decided.isEmpty()) {
            decidedRows.append("<tr><td colspan='7' class='muted'>No decisions made yet.</td></tr>");
        } else {
            for (Loan loan : decided) {
                Account applicant = repo.findByAccountNumber(loan.accountNumber);
                String applicantName = (applicant != null) ? applicant.name : "Unknown";
                decidedRows.append("<tr>")
                        .append("<td>#").append(loan.id).append("</td>")
                        .append("<td>").append(loan.accountNumber).append(" - ").append(escape(applicantName)).append("</td>")
                        .append("<td>$").append(fmt(loan.amount)).append("</td>")
                        .append("<td>").append(escape(loan.purpose)).append("</td>")
                        .append("<td><span class='badge ").append(loan.status).append("'>").append(loan.status).append("</span></td>")
                        .append("<td>").append(escape(loan.decidedBy)).append("</td>")
                        .append("<td>").append(loanDocumentLinks(loan)).append("</td>")
                        .append("</tr>");
            }
        }

        return """
            <!DOCTYPE html><html><head><meta charset="utf-8"><title>Employee Dashboard</title>%s</head>
            <body>
              <div class="card wide">
                <h1>Welcome, %s</h1>
                <div class="muted">Employee Loan Approval Dashboard</div>
                %s
                %s

                <div class="section-title">Pending Applications</div>
                <table class="loans">
                  <tr><th>ID</th><th>Account</th><th>Amount</th><th>Purpose</th><th>Applied</th><th>Documents</th><th>Action</th></tr>
                  %s
                </table>

                <div class="section-title">Recently Decided</div>
                <table class="loans">
                  <tr><th>ID</th><th>Account</th><th>Amount</th><th>Purpose</th><th>Status</th><th>Decided By</th><th>Documents</th></tr>
                  %s
                </table>

                <form action="/employee/logout" method="GET" style="margin-top:20px;">
                  <button type="submit" class="secondary">Log Out</button>
                </form>
              </div>
            </body></html>
            """.formatted(STYLE, escape(empName), msgHtml, errHtml, pendingRows.toString(), decidedRows.toString());
    }
}

// ==================== Account ====================
class Account {

    long accountNumber;
    String ownerId;   // shared across every account belonging to the same person
    String name;
    int pin;
    double balance;
    String label;     // e.g. "Savings", "Salary", "Joint" - helps tell a person's own accounts apart

    Account(long accountNumber, String ownerId, String name, int pin, double balance, String label) {
        this.accountNumber = accountNumber;
        this.ownerId = ownerId;
        this.name = name;
        this.pin = pin;
        this.balance = balance;
        this.label = label;
    }

    String toLine() {
        return accountNumber + "|" + ownerId + "|" + name + "|" + pin + "|" + balance + "|" + label;
    }
}

// ==================== AccountRepository ====================
class AccountRepository {

    private final Path filePath;

    AccountRepository(String fileName) {
        this.filePath = resolveFile(fileName);
    }

    private static Path resolveFile(String fileName) {
        List<Path> candidates = new ArrayList<>();

        candidates.add(Paths.get(fileName));
        candidates.add(Paths.get("..", fileName));

        try {
            Path codeLocation = Paths.get(
                    AccountRepository.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            Path codeDir = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            if (codeDir != null) {
                candidates.add(codeDir.resolve(fileName));
                candidates.add(codeDir.resolveSibling(fileName));
                if (codeDir.getParent() != null) {
                    candidates.add(codeDir.getParent().resolve(fileName));
                }
            }
        } catch (Exception ignored) {
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                System.out.println("Using database file: " + candidate.toAbsolutePath());
                return candidate;
            }
        }

        System.out.println("WARNING: could not find '" + fileName + "' in any of these locations:");
        for (Path candidate : candidates) {
            System.out.println("   - " + candidate.toAbsolutePath());
        }
        System.out.println("Current working directory is: " + Paths.get("").toAbsolutePath());
        System.out.println("Falling back to: " + Paths.get(fileName).toAbsolutePath());
        System.out.println("If logins fail with 'Account not found', copy/move account.txt next to");
        System.out.println("the working directory shown above, or run the app from that folder.");

        return Paths.get(fileName);
    }

    synchronized List<Account> findAll() {
        List<Account> accounts = new ArrayList<>();

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                accounts.add(parseLine(line));
            }
        } catch (IOException e) {
            System.out.println("Error reading account file: " + e.getMessage());
        }

        return accounts;
    }

    synchronized Account findByAccountNumber(long accountNumber) {
        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Account acc = parseLine(line);
                if (acc.accountNumber == accountNumber) {
                    return acc;
                }
            }
        } catch (IOException e) {
            System.out.println("Error reading account file: " + e.getMessage());
        }
        return null;
    }

    synchronized boolean update(Account updatedAccount) {
        List<String> lines = new ArrayList<>();
        boolean found = false;

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Account acc = parseLine(line);
                if (acc.accountNumber == updatedAccount.accountNumber) {
                    lines.add(updatedAccount.toLine());
                    found = true;
                } else {
                    lines.add(line);
                }
            }
        } catch (IOException e) {
            System.out.println("Error reading account file: " + e.getMessage());
            return false;
        }

        if (!found) {
            return false;
        }

        try (BufferedWriter bw = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            for (String line : lines) {
                bw.write(line);
                bw.newLine();
            }
        } catch (IOException e) {
            System.out.println("Error writing account file: " + e.getMessage());
            return false;
        }

        return true;
    }

    synchronized boolean insert(Account newAccount) {
        if (findByAccountNumber(newAccount.accountNumber) != null) {
            return false;
        }
        try (BufferedWriter bw = Files.newBufferedWriter(
                filePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            bw.write(newAccount.toLine());
            bw.newLine();
        } catch (IOException e) {
            System.out.println("Error appending to account file: " + e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Every account sharing the given ownerId - i.e. every account the same
     * person holds.
     */
    synchronized List<Account> findByOwnerId(String ownerId) {
        List<Account> result = new ArrayList<>();
        for (Account acc : findAll()) {
            if (acc.ownerId.equals(ownerId)) {
                result.add(acc);
            }
        }
        result.sort((a, b) -> Long.compare(a.accountNumber, b.accountNumber));
        return result;
    }

    /**
     * Moves money from one account to another, whether the two accounts belong
     * to the same person (self-transfer) or different people. Both balances are
     * read and rewritten in a single pass over the file so the transfer is
     * all-or-nothing - there's no window where money has left one account but
     * not yet reached the other.
     */
    synchronized TransferResult transfer(long fromAccountNumber, long toAccountNumber, double amount) {
        if (fromAccountNumber == toAccountNumber) {
            return TransferResult.failure("You can't transfer an account to itself.");
        }
        if (amount <= 0) {
            return TransferResult.failure("Transfer amount must be positive.");
        }

        List<Account> accounts = findAll();
        Account from = null, to = null;
        for (Account acc : accounts) {
            if (acc.accountNumber == fromAccountNumber) {
                from = acc;
            }
            if (acc.accountNumber == toAccountNumber) {
                to = acc;
            }
        }

        if (from == null) {
            return TransferResult.failure("Your account could not be found.");
        }
        if (to == null) {
            return TransferResult.failure("Destination account " + toAccountNumber + " was not found.");
        }
        if (from.balance < amount) {
            return TransferResult.failure("Insufficient balance.");
        }

        from.balance -= amount;
        to.balance += amount;

        try (BufferedWriter bw = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            for (Account acc : accounts) {
                bw.write(acc.toLine());
                bw.newLine();
            }
        } catch (IOException e) {
            System.out.println("Error writing account file during transfer: " + e.getMessage());
            return TransferResult.failure("Could not save the transfer. Please try again.");
        }

        return TransferResult.success(from, to);
    }

    private Account parseLine(String line) {
        String[] data = line.split("\\|", -1);
        long accNo = Long.parseLong(data[0].trim());
        String ownerId = data[1].trim();
        String name = data[2].trim();
        int pin = Integer.parseInt(data[3].trim());
        double balance = Double.parseDouble(data[4].trim());
        String label = data.length > 5 ? data[5].trim() : "Savings";
        return new Account(accNo, ownerId, name, pin, balance, label);
    }

    /**
     * Result of an AccountRepository.transfer(...) call.
     */
    static class TransferResult {

        final boolean ok;
        final String reason;   // populated only when !ok
        final Account from;    // populated only when ok - post-transfer state
        final Account to;      // populated only when ok - post-transfer state

        private TransferResult(boolean ok, String reason, Account from, Account to) {
            this.ok = ok;
            this.reason = reason;
            this.from = from;
            this.to = to;
        }

        static TransferResult success(Account from, Account to) {
            return new TransferResult(true, null, from, to);
        }

        static TransferResult failure(String reason) {
            return new TransferResult(false, reason, null, null);
        }
    }
}

// ==================== Loan ====================
class Loan {

    long id;
    long accountNumber;
    double amount;
    String purpose;
    String status;      // PENDING, APPROVED, REJECTED
    String appliedDate;
    String decidedBy;    // employee username who approved/rejected, or "" if still pending
    String bankStatementFile; // filename under uploads/, or "" if none
    String salarySlipFile;    // filename under uploads/, or "" if none

    Loan(long id, long accountNumber, double amount, String purpose,
            String status, String appliedDate, String decidedBy,
            String bankStatementFile, String salarySlipFile) {
        this.id = id;
        this.accountNumber = accountNumber;
        this.amount = amount;
        this.purpose = purpose;
        this.status = status;
        this.appliedDate = appliedDate;
        this.decidedBy = decidedBy;
        this.bankStatementFile = bankStatementFile;
        this.salarySlipFile = salarySlipFile;
    }

    String toLine() {
        return id + "|" + accountNumber + "|" + amount + "|" + purpose + "|" + status + "|" + appliedDate
                + "|" + decidedBy + "|" + bankStatementFile + "|" + salarySlipFile;
    }
}

// ==================== LoanRepository ====================
/**
 * All reads/writes to loans.txt happen here, mirroring how AccountRepository
 * owns account.txt. File format (one loan per line):
 * id|accountNumber|amount|purpose|status|appliedDate|decidedBy status is one
 * of: PENDING, APPROVED, REJECTED
 */
class LoanRepository {

    private final Path filePath;

    LoanRepository(String fileName) {
        this.filePath = resolveFile(fileName);
    }

    private static Path resolveFile(String fileName) {
        List<Path> candidates = new ArrayList<>();

        candidates.add(Paths.get(fileName));
        candidates.add(Paths.get("..", fileName));

        try {
            Path codeLocation = Paths.get(
                    LoanRepository.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            Path codeDir = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            if (codeDir != null) {
                candidates.add(codeDir.resolve(fileName));
                candidates.add(codeDir.resolveSibling(fileName));
                if (codeDir.getParent() != null) {
                    candidates.add(codeDir.getParent().resolve(fileName));
                }
            }
        } catch (Exception ignored) {
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                System.out.println("Using loans file: " + candidate.toAbsolutePath());
                return candidate;
            }
        }

        // loans.txt doesn't need to pre-exist - create it next to account.txt's
        // first candidate location the first time a loan is submitted.
        Path fallback = candidates.get(0);
        System.out.println("No existing loans file found - will create: " + fallback.toAbsolutePath());
        return fallback;
    }

    synchronized List<Loan> findAll() {
        List<Loan> loans = new ArrayList<>();

        if (!Files.exists(filePath)) {
            return loans;
        }

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                loans.add(parseLine(line));
            }
        } catch (IOException e) {
            System.out.println("Error reading loans file: " + e.getMessage());
        }

        return loans;
    }

    synchronized List<Loan> findByAccountNumber(long accountNumber) {
        List<Loan> result = new ArrayList<>();
        for (Loan loan : findAll()) {
            if (loan.accountNumber == accountNumber) {
                result.add(loan);
            }
        }
        result.sort((a, b) -> Long.compare(b.id, a.id));
        return result;
    }

    synchronized List<Loan> findByStatus(String status) {
        List<Loan> result = new ArrayList<>();
        for (Loan loan : findAll()) {
            if (loan.status.equalsIgnoreCase(status)) {
                result.add(loan);
            }
        }
        result.sort((a, b) -> Long.compare(a.id, b.id));
        return result;
    }

    synchronized Loan findById(long id) {
        for (Loan loan : findAll()) {
            if (loan.id == id) {
                return loan;
            }
        }
        return null;
    }

    synchronized boolean insert(Loan newLoan) {
        try (BufferedWriter bw = Files.newBufferedWriter(
                filePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            bw.write(newLoan.toLine());
            bw.newLine();
        } catch (IOException e) {
            System.out.println("Error appending to loans file: " + e.getMessage());
            return false;
        }
        return true;
    }

    synchronized boolean update(Loan updatedLoan) {
        List<Loan> loans = findAll();
        boolean found = false;

        try (BufferedWriter bw = Files.newBufferedWriter(filePath, StandardCharsets.UTF_8)) {
            for (Loan loan : loans) {
                if (loan.id == updatedLoan.id) {
                    bw.write(updatedLoan.toLine());
                    found = true;
                } else {
                    bw.write(loan.toLine());
                }
                bw.newLine();
            }
        } catch (IOException e) {
            System.out.println("Error writing loans file: " + e.getMessage());
            return false;
        }

        return found;
    }

    synchronized long nextId() {
        long max = 0;
        for (Loan loan : findAll()) {
            max = Math.max(max, loan.id);
        }
        return max + 1;
    }

    private Loan parseLine(String line) {
        String[] data = line.split("\\|", -1);
        long id = Long.parseLong(data[0].trim());
        long accNo = Long.parseLong(data[1].trim());
        double amount = Double.parseDouble(data[2].trim());
        String purpose = data[3].trim();
        String status = data[4].trim();
        String appliedDate = data.length > 5 ? data[5].trim() : "";
        String decidedBy = data.length > 6 ? data[6].trim() : "";
        String bankStatementFile = data.length > 7 ? data[7].trim() : "";
        String salarySlipFile = data.length > 8 ? data[8].trim() : "";
        return new Loan(id, accNo, amount, purpose, status, appliedDate, decidedBy, bankStatementFile, salarySlipFile);
    }
}

// ==================== Transfer ====================
class Transfer {

    long id;
    long fromAccount;
    long toAccount;
    double amount;
    String note;
    String date;
    String type; // "SELF" (same owner) or "EXTERNAL" (different owner)

    Transfer(long id, long fromAccount, long toAccount, double amount, String note, String date, String type) {
        this.id = id;
        this.fromAccount = fromAccount;
        this.toAccount = toAccount;
        this.amount = amount;
        this.note = note;
        this.date = date;
        this.type = type;
    }

    String toLine() {
        return id + "|" + fromAccount + "|" + toAccount + "|" + amount + "|" + note + "|" + date + "|" + type;
    }
}

// ==================== TransferRepository ====================
/**
 * Append-only log of completed transfers, written to transfers.txt after
 * AccountRepository.transfer(...) has already moved the money. This is a
 * history/audit trail only - it is never consulted to decide whether a transfer
 * is allowed. File format (one transfer per line):
 * id|fromAccount|toAccount|amount|note|date|type
 */
class TransferRepository {

    private final Path filePath;

    TransferRepository(String fileName) {
        this.filePath = resolveFile(fileName);
    }

    private static Path resolveFile(String fileName) {
        List<Path> candidates = new ArrayList<>();

        candidates.add(Paths.get(fileName));
        candidates.add(Paths.get("..", fileName));

        try {
            Path codeLocation = Paths.get(
                    TransferRepository.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            Path codeDir = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            if (codeDir != null) {
                candidates.add(codeDir.resolve(fileName));
                candidates.add(codeDir.resolveSibling(fileName));
                if (codeDir.getParent() != null) {
                    candidates.add(codeDir.getParent().resolve(fileName));
                }
            }
        } catch (Exception ignored) {
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                System.out.println("Using transfers file: " + candidate.toAbsolutePath());
                return candidate;
            }
        }

        // transfers.txt doesn't need to pre-exist - create it next to account.txt's
        // first candidate location the first time a transfer is made.
        Path fallback = candidates.get(0);
        System.out.println("No existing transfers file found - will create: " + fallback.toAbsolutePath());
        return fallback;
    }

    synchronized List<Transfer> findAll() {
        List<Transfer> transfers = new ArrayList<>();

        if (!Files.exists(filePath)) {
            return transfers;
        }

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                transfers.add(parseLine(line));
            }
        } catch (IOException e) {
            System.out.println("Error reading transfers file: " + e.getMessage());
        }

        return transfers;
    }

    synchronized List<Transfer> findByAccountNumber(long accountNumber) {
        List<Transfer> result = new ArrayList<>();
        for (Transfer t : findAll()) {
            if (t.fromAccount == accountNumber || t.toAccount == accountNumber) {
                result.add(t);
            }
        }
        result.sort((a, b) -> Long.compare(b.id, a.id));
        return result;
    }

    synchronized boolean insert(Transfer newTransfer) {
        try (BufferedWriter bw = Files.newBufferedWriter(
                filePath, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND)) {
            bw.write(newTransfer.toLine());
            bw.newLine();
        } catch (IOException e) {
            System.out.println("Error appending to transfers file: " + e.getMessage());
            return false;
        }
        return true;
    }

    synchronized long nextId() {
        long max = 0;
        for (Transfer t : findAll()) {
            max = Math.max(max, t.id);
        }
        return max + 1;
    }

    private Transfer parseLine(String line) {
        String[] data = line.split("\\|", -1);
        long id = Long.parseLong(data[0].trim());
        long fromAccount = Long.parseLong(data[1].trim());
        long toAccount = Long.parseLong(data[2].trim());
        double amount = Double.parseDouble(data[3].trim());
        String note = data.length > 4 ? data[4].trim() : "";
        String date = data.length > 5 ? data[5].trim() : "";
        String type = data.length > 6 ? data[6].trim() : "EXTERNAL";
        return new Transfer(id, fromAccount, toAccount, amount, note, date, type);
    }
}

// ==================== Employee ====================
class Employee {

    String username;
    String password;
    String name;

    Employee(String username, String password, String name) {
        this.username = username;
        this.password = password;
        this.name = name;
    }

    String toLine() {
        return username + "|" + password + "|" + name;
    }
}

// ==================== EmployeeRepository ====================
/**
 * Reads employees.txt (bank staff who can approve/reject loans). File format
 * (one employee per line): username|password|name
 */
class EmployeeRepository {

    private final Path filePath;

    EmployeeRepository(String fileName) {
        this.filePath = resolveFile(fileName);
    }

    private static Path resolveFile(String fileName) {
        List<Path> candidates = new ArrayList<>();

        candidates.add(Paths.get(fileName));
        candidates.add(Paths.get("..", fileName));

        try {
            Path codeLocation = Paths.get(
                    EmployeeRepository.class.getProtectionDomain()
                            .getCodeSource().getLocation().toURI());
            Path codeDir = Files.isDirectory(codeLocation) ? codeLocation : codeLocation.getParent();
            if (codeDir != null) {
                candidates.add(codeDir.resolve(fileName));
                candidates.add(codeDir.resolveSibling(fileName));
                if (codeDir.getParent() != null) {
                    candidates.add(codeDir.getParent().resolve(fileName));
                }
            }
        } catch (Exception ignored) {
        }

        for (Path candidate : candidates) {
            if (Files.exists(candidate)) {
                System.out.println("Using employees file: " + candidate.toAbsolutePath());
                return candidate;
            }
        }

        System.out.println("WARNING: could not find '" + fileName + "' in any of these locations:");
        for (Path candidate : candidates) {
            System.out.println("   - " + candidate.toAbsolutePath());
        }
        System.out.println("Employee login will fail until this file exists next to account.txt.");

        return candidates.get(0);
    }

    synchronized List<Employee> findAll() {
        List<Employee> employees = new ArrayList<>();

        if (!Files.exists(filePath)) {
            return employees;
        }

        try (BufferedReader br = Files.newBufferedReader(filePath, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                if (line.isBlank()) {
                    continue;
                }
                employees.add(parseLine(line));
            }
        } catch (IOException e) {
            System.out.println("Error reading employees file: " + e.getMessage());
        }

        return employees;
    }

    synchronized Employee findByUsername(String username) {
        for (Employee emp : findAll()) {
            if (emp.username.equalsIgnoreCase(username)) {
                return emp;
            }
        }
        return null;
    }

    private Employee parseLine(String line) {
        String[] data = line.split("\\|", -1);
        String username = data[0].trim();
        String password = data[1].trim();
        String name = data.length > 2 ? data[2].trim() : username;
        return new Employee(username, password, name);
    }
}
