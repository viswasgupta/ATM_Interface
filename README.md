# 🏧 ATM Interface (Java CLI Project)

A fully functional **Command-Line ATM Simulation** built using **Java**, following strong **OOP principles**, secure authentication, file-based persistent storage, and structured transaction workflows. This project demonstrates practical banking operations and real-world system behavior using a simple and modular design.

---

## 🚀 Features

### 🔐 Authentication
- Secure login using **Account ID + PIN**
- 3 failed attempts lockout mechanism
- PIN change functionality with validation

### 💰 Banking Operations
- Withdraw money with balance validation
- Deposit money with error-handled workflows
- Check account balance at any time
- View last 10 transactions

### 🗂️ Persistent Storage (File I/O)
- Accounts stored in `accounts.csv`
- Transaction history stored in `tx_<accountId>.log`
- Data remains saved even after closing the app

### 🧱 OOP Architecture
- `Account`  
- `ATMService`  
- `UserSession`  
- `Transaction`

Designed for clarity, scalability, and clean modular code.

### 🛡️ Error Handling
- Handles invalid inputs safely  
- Prevents negative deposits/withdrawals  
- Prevents system crashes using structured exceptions  

---

## 📂 Project Structure
- Solution.java # Main project file
- accounts.csv # Auto-created account storage
- tx_<id>.log # Transaction history for each account
