package dev.opencode.mobile.domain.t3

import java.net.URLEncoder

object T3TokenExchange {
    const val GRANT_TYPE = "urn:ietf:params:oauth:grant-type:token-exchange"
    const val SUBJECT_TOKEN_TYPE = "urn:t3:params:oauth:token-type:environment-bootstrap"
    const val REQUESTED_TOKEN_TYPE = "urn:ietf:params:oauth:token-type:access_token"
    const val CLIENT_LABEL = "OpenCode Mobile"
    const val CLIENT_DEVICE_TYPE = "mobile"

    fun formFields(pairingToken: String): Map<String, String> =
        mapOf(
            "grant_type" to GRANT_TYPE,
            "subject_token" to pairingToken,
            "subject_token_type" to SUBJECT_TOKEN_TYPE,
            "requested_token_type" to REQUESTED_TOKEN_TYPE,
            "client_label" to CLIENT_LABEL,
            "client_device_type" to CLIENT_DEVICE_TYPE,
        )

    fun encodeBody(pairingToken: String): String =
        formFields(pairingToken).entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())
}
