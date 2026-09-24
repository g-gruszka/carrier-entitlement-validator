package com.carrier.entitlement.validator.data.model

data class CamaraVerifyResult(
    val devicePhoneNumberVerified: Boolean,
    val xCorrelator: String?,
    val statusCode: Int,
    val rawResponse: String
)
