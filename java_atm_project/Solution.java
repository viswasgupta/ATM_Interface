import java.io.*;
import java.nio.file.*;
import java.text.SimpleDateFormat;
import java.util.*;

/*
 * Save this file as Solution.java
 * Compile: javac Solution.java
 * Run:     java Solution
 *
 * This program stores accounts in "accounts.csv" (CSV: accountId,name,pin,balance)
 * and per-account transaction history in "tx_{accountId}.log".
 * If no accounts file exists, a demo account is created:
 *   accountId: 1001, name: Demo User, PIN: 1234, balance: 1000.00
 */

class Account {
    private final String accountId;
    private final String name;
    private String pin; // stored as plaintext for demo; in real apps hash this
    private double balance;

    Account(String accountId, String name, String pin, double balance) {
        this.accountId = accountId;
        this.name = name;
        this.pin = pin;
        this.balance = balance;
    }

    String getAccountId() { return accountId; }
    String getName() { return name; }
    String getPin() { return pin; }
    double getBalance() { return balance; }

    void setPin(String newPin) { this.pin = newPin; }

    void deposit(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Deposit amount must be positive.");
        balance += amount;
    }

    void withdraw(double amount) {
        if (amount <= 0) throw new IllegalArgumentException("Withdrawal amount must be positive.");
        if (amount > balance) throw new IllegalArgumentException("Insufficient balance.");
        balance -= amount;
    }

    String toCsv() {
        return String.join(",", accountId, escapeCsv(name), pin, String.format(Locale.US, "%.2f", balance));
    }

    static String escapeCsv(String s) {
        if (s.contains(",") || s.contains("\"")) {
            return "\"" + s.replace("\"", "\"\"") + "\"";
        }
        return s;
    }

    static Account fromCsv(String line) throws IllegalArgumentException {
        // basic CSV split handling for our simple format
        // Note: does not handle all CSV edge cases; adequate for our controlled file
        List<String> parts = new ArrayList<>();
        boolean inQuotes = false;
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"' ) {
                inQuotes = !inQuotes;
            } else if (c == ',' && !inQuotes) {
                parts.add(cur.toString());
                cur.setLength(0);
            } else {
                cur.append(c);
            }
        }
        parts.add(cur.toString());
        if (parts.size() != 4) throw new IllegalArgumentException("Invalid account line: " + line);
        String acct = parts.get(0).trim();
        String name = parts.get(1).trim();
        String pin = parts.get(2).trim();
        double bal = Double.parseDouble(parts.get(3).trim());
        return new Account(acct, name, pin, bal);
    }
}

class Transaction {
    final Date timestamp;
    final String type;
    final double amount;
    final double balanceAfter;
    final String note;

    Transaction(Date timestamp, String type, double amount, double balanceAfter, String note) {
        this.timestamp = timestamp;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.note = note;
    }

    String toLogLine() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        return String.format("%s | %s | %.2f | Balance: %.2f | %s",
                fmt.format(timestamp), type, amount, balanceAfter, note == null ? "" : note);
    }
}

class ATMService {
    private static final String ACCOUNTS_FILE = "accounts.csv";
    private static final String TX_PREFIX = "tx_"; // tx_<accountId>.log

    private final Map<String, Account> accounts = new HashMap<>();

    ATMService() {
        try {
            loadAccounts();
        } catch (IOException e) {
            System.err.println("Warning: could not load accounts file: " + e.getMessage());
        }
    }

    private void loadAccounts() throws IOException {
        File f = new File(ACCOUNTS_FILE);
        if (!f.exists()) {
            // create demo account
            System.out.println("No accounts file found. Creating demo account (1001 / PIN 1234).");
            Account demo = new Account("1001", "Demo User", "1234", 1000.00);
            accounts.put(demo.getAccountId(), demo);
            persistAccounts(); // write demo
            return;
        }
        try (BufferedReader br = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                try {
                    Account a = Account.fromCsv(line);
                    accounts.put(a.getAccountId(), a);
                } catch (IllegalArgumentException ex) {
                    System.err.println("Skipping invalid account line: " + line);
                }
            }
        }
    }

    synchronized void persistAccounts() {
        // Write atomically: write to temp file then rename
        File tmp = new File(ACCOUNTS_FILE + ".tmp");
        try (PrintWriter pw = new PrintWriter(new FileWriter(tmp))) {
            for (Account a : accounts.values()) {
                pw.println(a.toCsv());
            }
            pw.flush();
            File orig = new File(ACCOUNTS_FILE);
            Files.move(tmp.toPath(), orig.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException e) {
            System.err.println("Failed to persist accounts: " + e.getMessage());
        } finally {
            if (tmp.exists()) tmp.delete();
        }
    }

    Account findAccount(String accountId) {
        return accounts.get(accountId);
    }

    synchronized void registerTransaction(Account account, String type, double amount, String note) {
        Transaction tx = new Transaction(new Date(), type, amount, account.getBalance(), note);
        String fname = TX_PREFIX + account.getAccountId() + ".log";
        try (PrintWriter pw = new PrintWriter(new FileWriter(fname, true))) {
            pw.println(tx.toLogLine());
        } catch (IOException e) {
            System.err.println("Warning: failed to write transaction log: " + e.getMessage());
        }
    }

    synchronized boolean authenticate(Account account, String pin) {
        return account.getPin().equals(pin);
    }

    synchronized void deposit(Account account, double amount) {
        account.deposit(amount);
        persistAccounts();
        registerTransaction(account, "DEPOSIT", amount, "User deposit");
    }

    synchronized boolean withdraw(Account account, double amount) {
        try {
            account.withdraw(amount);
            persistAccounts();
            registerTransaction(account, "WITHDRAW", amount, "User withdrawal");
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    synchronized boolean changePin(Account account, String newPin) {
        if (newPin == null || newPin.length() < 4) return false;
        account.setPin(newPin);
        persistAccounts();
        registerTransaction(account, "PIN_CHANGE", 0.0, "User changed PIN");
        return true;
    }

    List<String> readTransactionLog(Account account, int maxLines) {
        String fname = TX_PREFIX + account.getAccountId() + ".log";
        File f = new File(fname);
        if (!f.exists()) return Collections.emptyList();
        LinkedList<String> lines = new LinkedList<>();
        try (ReversedLinesFileReader rrf = new ReversedLinesFileReader(f)) {
            int count = 0;
            String l;
            while ((l = rrf.readLine()) != null && count < maxLines) {
                lines.add(l);
                count++;
            }
        } catch (IOException e) {
            System.err.println("Failed to read transaction log: " + e.getMessage());
        }
        return lines;
    }

    // Utility for reading transactions in reverse order (most recent first)
    // Since Java doesn't have a standard reversed lines reader, implement a simple fallback
    static class ReversedLinesFileReader implements Closeable {
        private final RandomAccessFile raf;
        private long pointer;

        ReversedLinesFileReader(File file) throws FileNotFoundException, IOException {
            this.raf = new RandomAccessFile(file, "r");
            this.pointer = raf.length();
        }

        String readLine() throws IOException {
            if (pointer <= 0) return null;
            StringBuilder sb = new StringBuilder();
            while (--pointer >= 0) {
                raf.seek(pointer);
                int b = raf.read();
                if (b == '\n' && sb.length() > 0) {
                    break;
                } else if (b != '\r') {
                    sb.append((char) b);
                }
            }
            if (sb.length() == 0 && pointer < 0) return null;
            return sb.reverse().toString();
        }

        @Override
        public void close() throws IOException {
            raf.close();
        }
    }
}

class UserSession {
    private final ATMService atm;
    private final Scanner in;
    private Account current;

    UserSession(ATMService atm, Scanner in) {
        this.atm = atm;
        this.in = in;
    }

    void start() {
        while (true) {
            System.out.println("\n=== Welcome to CLI ATM ===");
            System.out.println("1) Login");
            System.out.println("2) Create account");
            System.out.println("3) Exit");
            System.out.print("Choose: ");
            String choice = in.nextLine().trim();
            try {
                if (choice.equals("1")) {
                    if (login()) {
                        showMenu();
                    }
                } else if (choice.equals("2")) {
                    createAccount();
                } else if (choice.equals("3")) {
                    System.out.println("Thank you. Goodbye!");
                    break;
                } else {
                    System.out.println("Invalid option.");
                }
            } catch (Exception e) {
                System.err.println("Error: " + e.getMessage());
            }
        }
    }

    private boolean login() {
        System.out.print("Enter account ID: ");
        String acctId = in.nextLine().trim();
        Account acc = atm.findAccount(acctId);
        if (acc == null) {
            System.out.println("Account not found.");
            return false;
        }
        int attemptsLeft = 3;
        while (attemptsLeft > 0) {
            System.out.print("Enter PIN: ");
            String pin = in.nextLine().trim();
            if (atm.authenticate(acc, pin)) {
                System.out.println("Login successful. Welcome, " + acc.getName() + "!");
                current = acc;
                return true;
            } else {
                attemptsLeft--;
                System.out.println("Incorrect PIN. Attempts left: " + attemptsLeft);
            }
        }
        System.out.println("Too many failed attempts. Returning to main menu.");
        return false;
    }

    private void createAccount() {
        System.out.print("Enter new account ID (numeric): ");
        String id = in.nextLine().trim();
        if (id.isEmpty() || atm.findAccount(id) != null) {
            System.out.println("Invalid or already existing account ID.");
            return;
        }
        System.out.print("Enter your name: ");
        String name = in.nextLine().trim();
        String pin;
        while (true) {
            System.out.print("Set 4-digit PIN: ");
            pin = in.nextLine().trim();
            if (pin.matches("\\d{4}")) break;
            System.out.println("PIN must be 4 digits.");
        }
        double initial = 0.0;
        while (true) {
            System.out.print("Initial deposit (>=0): ");
            String s = in.nextLine().trim();
            try {
                initial = Double.parseDouble(s);
                if (initial < 0) throw new NumberFormatException();
                break;
            } catch (NumberFormatException e) {
                System.out.println("Enter a valid non-negative number.");
            }
        }
        Account newAcc = new Account(id, name, pin, initial);
        // persist to ATMService
        synchronized (atm) {
            // direct access; in more encapsulated design, ATMService would expose createAccount
            try {
                // use reflection of internal accounts map via persistence methods - simpler: write to file directly
                // To keep code simple, modify ATMService via simple approach:
                // (We will add new account via accounts map using persistAccounts)
                java.lang.reflect.Field accountsField = atm.getClass().getDeclaredField("accounts");
                accountsField.setAccessible(true);
                @SuppressWarnings("unchecked")
                Map<String, Account> map = (Map<String, Account>) accountsField.get(atm);
                map.put(newAcc.getAccountId(), newAcc);
                java.lang.reflect.Method persist = atm.getClass().getDeclaredMethod("persistAccounts");
                persist.setAccessible(true);
                persist.invoke(atm);
                // register initial deposit if any
                if (initial > 0.0) atm.registerTransaction(newAcc, "DEPOSIT", initial, "Initial deposit");
                System.out.println("Account created successfully.");
            } catch (Exception ex) {
                System.err.println("Failed to create account: " + ex.getMessage());
            }
        }
    }

    private void showMenu() {
        boolean keep = true;
        while (keep) {
            System.out.println("\n--- Account Menu ---");
            System.out.println("1) Balance inquiry");
            System.out.println("2) Deposit");
            System.out.println("3) Withdraw");
            System.out.println("4) View recent transactions");
            System.out.println("5) Change PIN");
            System.out.println("6) Logout");
            System.out.print("Choose: ");
            String opt = in.nextLine().trim();
            switch (opt) {
                case "1":
                    System.out.printf("Current balance: %.2f%n", current.getBalance());
                    break;
                case "2":
                    doDeposit();
                    break;
                case "3":
                    doWithdraw();
                    break;
                case "4":
                    showTransactions();
                    break;
                case "5":
                    changePin();
                    break;
                case "6":
                    current = null;
                    keep = false;
                    System.out.println("Logged out.");
                    break;
                default:
                    System.out.println("Invalid option.");
            }
        }
    }

    private void doDeposit() {
        System.out.print("Enter amount to deposit: ");
        String s = in.nextLine().trim();
        try {
            double amt = Double.parseDouble(s);
            atm.deposit(current, amt);
            System.out.printf("Deposited %.2f. New balance: %.2f%n", amt, current.getBalance());
        } catch (NumberFormatException e) {
            System.out.println("Enter a valid number.");
        } catch (IllegalArgumentException e) {
            System.out.println("Deposit failed: " + e.getMessage());
        }
    }

    private void doWithdraw() {
        System.out.print("Enter amount to withdraw: ");
        String s = in.nextLine().trim();
        try {
            double amt = Double.parseDouble(s);
            boolean ok = atm.withdraw(current, amt);
            if (ok) {
                System.out.printf("Withdrew %.2f. New balance: %.2f%n", amt, current.getBalance());
            } else {
                System.out.println("Withdrawal failed: insufficient funds or invalid amount.");
            }
        } catch (NumberFormatException e) {
            System.out.println("Enter a valid number.");
        }
    }

    private void showTransactions() {
        System.out.println("Most recent transactions:");
        List<String> lines = atm.readTransactionLog(current, 10);
        if (lines.isEmpty()) {
            System.out.println("(no transactions found)");
            return;
        }
        for (String l : lines) {
            System.out.println(l);
        }
    }

    private void changePin() {
        System.out.print("Enter current PIN: ");
        String cur = in.nextLine().trim();
        if (!atm.authenticate(current, cur)) {
            System.out.println("Incorrect current PIN.");
            return;
        }
        System.out.print("Enter new 4-digit PIN: ");
        String np = in.nextLine().trim();
        if (!np.matches("\\d{4}")) {
            System.out.println("PIN must be 4 digits.");
            return;
        }
        if (atm.changePin(current, np)) {
            System.out.println("PIN changed successfully.");
        } else {
            System.out.println("Failed to change PIN.");
        }
    }
}

public class Solution {
    public static void main(String[] args) {
        ATMService atm = new ATMService();
        Scanner in = new Scanner(System.in);
        UserSession sess = new UserSession(atm, in);

        // brief help header
        System.out.println("CLI ATM - Secure demo. Data stored locally in files.");
        sess.start();
        in.close();
    }
}
