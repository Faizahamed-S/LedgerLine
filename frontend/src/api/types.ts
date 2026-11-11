// Mirrors the backend DTO records field for field. Money is always an integer number of paise.

export type Role = 'ADMIN' | 'HOLDER' | 'AUDITOR';
export type UserStatus = 'ACTIVE' | 'DISABLED';
export type AccountType = 'CUSTOMER' | 'EXTERNAL';
export type AccountStatus = 'PENDING' | 'ACTIVE' | 'FROZEN';
export type TransactionKind = 'DEPOSIT' | 'WITHDRAWAL' | 'TRANSFER';
export type TransactionStatus = 'POSTED' | 'REJECTED';
export type StatementDirection = 'DEBIT' | 'CREDIT' | 'REFUSED';
export type RejectReason = 'NOT_AUTHORISED' | 'ACCOUNT_NOT_ACTIVE' | 'DESTINATION_UNAVAILABLE' | 'INSUFFICIENT_FUNDS';

/** com.ledgerline.common.ApiError */
export interface ApiError {
  status: number;
  error: string;
  message: string;
  timestamp: string;
}

// ---- auth ----

export interface RegisterRequest {
  email: string;
  fullName: string;
  password: string;
}

export interface LoginRequest {
  email: string;
  password: string;
}

export interface UserResponse {
  id: number;
  email: string;
  fullName: string;
  role: Role;
}

export interface LoginResponse {
  token: string;
  expiresAt: string;
  user: UserResponse;
}

// ---- admin users ----

export interface AdminUserResponse {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  status: UserStatus;
  createdAt: string;
}

export interface CreateUserRequest {
  email: string;
  fullName: string;
  password: string;
  role: Role;
}

export interface UpdateUserStatusRequest {
  status: UserStatus;
}

// ---- accounts ----

export interface AccountResponse {
  id: number;
  name: string;
  type: AccountType;
  status: AccountStatus;
  balancePaise: number;
  ownerId: number | null;
  ownerEmail: string | null;
  createdAt: string;
}

export interface CreateAccountRequest {
  name: string;
}

export interface UpdateAccountStatusRequest {
  status: AccountStatus;
}

// ---- money ----

export interface AmountRequest {
  amountPaise: number;
}

export interface TransferRequest {
  fromAccountId: number;
  toAccountId: number;
  amountPaise: number;
}

export interface TransactionResponse {
  transactionId: number;
  kind: TransactionKind;
  status: TransactionStatus;
  amountPaise: number;
  fromAccountId: number | null;
  toAccountId: number | null;
  rejectReason: RejectReason | null;
  createdAt: string;
}

// ---- statement ----

export interface StatementLine {
  createdAt: string;
  transactionId: number;
  kind: TransactionKind;
  direction: StatementDirection;
  amountPaise: number;
  balanceAfterPaise: number | null;
  counterpartyAccountId: number | null;
  initiatedByEmail: string;
  status: TransactionStatus;
  rejectReason: RejectReason | null;
}

export interface StatementResponse {
  accountId: number;
  balancePaise: number;
  lines: StatementLine[];
}

// ---- audit ----

export interface AuditLogResponse {
  id: number;
  createdAt: string;
  actorId: number | null;
  actorEmail: string | null;
  action: string;
  targetType: string | null;
  targetId: number | null;
  details: string | null;
}

export interface MismatchedAccount {
  accountId: number;
  balancePaise: number;
  ledgerSumPaise: number;
}

export interface ReconciliationResponse {
  ledgerSumPaise: number;
  customerTotalPaise: number;
  externalBalancePaise: number;
  balanced: boolean;
  mismatchedAccounts: MismatchedAccount[];
  postedCount: number;
  rejectedCount: number;
  duplicateRequestCount: number;
  checkedAt: string;
}
