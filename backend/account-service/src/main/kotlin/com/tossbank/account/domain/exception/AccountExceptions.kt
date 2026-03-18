import com.tossbank.account.domain.exception.AccountErrorCode
import com.tossbank.common.exception.CustomException

// 조회 관련 예외
class AccountNotFoundException : CustomException(AccountErrorCode.ACCOUNT_NOT_FOUND)
class UnauthorizedAccountAccessException : CustomException(AccountErrorCode.UNAUTHORIZED_ACCOUNT_ACCESS)

// 비즈니스 룰 예외
class InvalidDepositAmountException : CustomException(AccountErrorCode.INVALID_DEPOSIT_AMOUNT)
class InvalidWithdrawAmountException : CustomException(AccountErrorCode.INVALID_WITHDRAW_AMOUNT)
class InsufficientBalanceException : CustomException(AccountErrorCode.INSUFFICIENT_BALANCE)

// 계좌 상태 예외
class AccountSuspendedException : CustomException(AccountErrorCode.ACCOUNT_SUSPENDED)
class AccountAlreadyClosedException : CustomException(AccountErrorCode.ACCOUNT_ALREADY_CLOSED)