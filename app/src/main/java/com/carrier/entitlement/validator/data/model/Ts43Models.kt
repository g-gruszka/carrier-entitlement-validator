package com.carrier.entitlement.validator.data.model

data class EapChallengeData(
    val eapRelayPacket: String,
    val sessionId: String,
    val eapSession: String,
    val rawJson: String
)

data class EapExtractedChallenge(
    val eapId: Int,
    val randHex: String,
    val autnHex: String,
    val macHex: String
)

data class EapAuthResult(
    val resHex: String,
    val autsHex: String? = null,
    val isSyncFailure: Boolean = false,
    val source: String // "USIM_HARDWARE" or "SOFTWARE_ENGINE"
)

data class TemporaryTokenResult(
    val token: String,
    val validUntilTimestamp: Long,
    val rawXml: String
)

data class Ts43VerifyResult(
    val operationResult: Int, // 1 = Match, 0 = No Match
    val isMatch: Boolean,
    val rawXml: String
)
