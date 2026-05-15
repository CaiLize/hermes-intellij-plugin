package com.hermes.intellij.api

/**
 * Exception thrown when the Hermes API returns an error response.
 */
class HermesApiException(val statusCode: Int, message: String) : Exception(message)
