
import com.tossbank.account.domain.exception.AccountErrorCode
import com.tossbank.common.exception.CustomException

// 조회 관련 예외
class AccountNotFoundException : CustomException(AccountErrorCode.ACCOUNT_NOT_FOUND)
class UnauthorizedAccountAccessException : CustomException(AccountErrorCode.UNAUTHORIZED_ACCOUNT_ACCESS)

// 입출금 비즈니스 룰 예외
class InvalidDepositAmountException : CustomException(AccountErrorCode.INVALID_DEPOSIT_AMOUNT)
class InvalidWithdrawAmountException : CustomException(AccountErrorCode.INVALID_WITHDRAW_AMOUNT)
class InsufficientBalanceException : CustomException(AccountErrorCode.INSUFFICIENT_BALANCE)

// 계좌 상태 예외
class InvalidAccountStatusException : CustomException(AccountErrorCode.INVALID_ACCOUNT_STATUS)
class AccountSuspendedException : CustomException(AccountErrorCode.ACCOUNT_SUSPENDED)
class AccountAlreadyClosedException : CustomException(AccountErrorCode.ACCOUNT_ALREADY_CLOSED)
class AccountHasRemainingBalanceException : CustomException(AccountErrorCode.ACCOUNT_HAS_REMAINING_BALANCE)

// 이체 예외
class TransferSameAccountException : CustomException(AccountErrorCode.TRANSFER_SAME_ACCOUNT)
class LockAcquisitionException : CustomException(AccountErrorCode.LOCK_ACQUISITION_FAILED)

class ExternalTransferNotSupportedException : CustomException(AccountErrorCode.EXTERNAL_TRANSFER_NOT_SUPPORTED)
class InvalidTransferStateTransitionException : CustomException(AccountErrorCode.INVALID_TRANSFER_STATE_TRANSITION)

// 타행 이체 — 외부 응답
class ExternalBankApiException(
    val statusCode: Int,
    message: String,
) : RuntimeException(message) {
    val isClientError get() = statusCode in 400..499
    val isServerError get() = statusCode >= 500
}
class ExternalTransferFailedException : CustomException(AccountErrorCode.EXTERNAL_TRANSFER_FAILED)
class ExternalTransferTimeoutException : CustomException(AccountErrorCode.EXTERNAL_TRANSFER_TIMEOUT,)
class ExternalTransferServerErrorException : CustomException(AccountErrorCode.EXTERNAL_TRANSFER_SERVER_ERROR)
class ExternalTransferUnknownException : CustomException(AccountErrorCode.EXTERNAL_TRANSFER_UNKNOWN)

class CompensationFailedException(cause: Exception) : RuntimeException(cause)