package com.tossbank.transfer.domain.exception

import com.tossbank.common.exception.CustomException

class ExternalBankClientException(message: String) : RuntimeException(message)
class ExternalBankServerException(message: String) : RuntimeException(message)
class ExternalBankTimeoutException(message: String) : RuntimeException(message)

class AccountNotFoundException : CustomException(TransferErrorCode.ACCOUNT_NOT_FOUND)
class SameAccountTransferException : CustomException(TransferErrorCode.SAME_ACCOUNT_TRANSFER)
class InsufficientBalanceException : CustomException(TransferErrorCode.INSUFFICIENT_BALANCE)
class DuplicateTransferException : CustomException(TransferErrorCode.DUPLICATE_TRANSFER)
class LockAcquisitionFailedException : CustomException(TransferErrorCode.LOCK_ACQUISITION_FAILED)
class ExternalTransferFailedException : CustomException(TransferErrorCode.EXTERNAL_TRANSFER_FAILED)
class ExternalTransferInProgressException : CustomException(TransferErrorCode.EXTERNAL_TRANSFER_IN_PROGRESS)
class ExternalTransferManualRequiredException : CustomException(TransferErrorCode.EXTERNAL_TRANSFER_MANUAL_REQUIRED)

// 이체 관련 예외
class TransferFailedException : CustomException(TransferErrorCode.TRANSFER_FAILED)
class TransferNotFoundException : CustomException(TransferErrorCode.TRANSFER_NOT_FOUND)
class UnauthorizedTransferAccessException : CustomException(TransferErrorCode.UNAUTHORIZED)
class TransferInProgressException(val sagaId: Long) : CustomException(TransferErrorCode.TRANSFER_IN_PROGRESS)

class InvalidTransferStateTransitionException : CustomException(TransferErrorCode.INVALID_TRANSFER_STATE_TRANSITION)