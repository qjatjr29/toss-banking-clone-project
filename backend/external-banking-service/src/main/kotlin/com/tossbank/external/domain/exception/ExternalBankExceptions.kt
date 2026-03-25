package com.tossbank.external.domain.exception

import com.tossbank.common.exception.CustomException

class ExternalBankClientException(errorCode: ExternalBankErrorCode) : CustomException(errorCode)
class ExternalBankServerException(errorCode: ExternalBankErrorCode) : CustomException(errorCode)