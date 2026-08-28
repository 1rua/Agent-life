package com.agentlife.gateway.schema

/**
 * Named entry point for JCS digests.
 *
 * The name is kept explicit because "hash this JSON" is ambiguous: only the
 * RFC 8785 canonical form produces a digest that matches the contract, so
 * callers should have to say they want the canonical one.
 */
object CanonicalJson {

    fun canonical(value: JsonValue): String = Json.canonical(value)

    fun sha256(value: JsonValue): String = Json.sha256(value)

    fun sha256Hex(value: JsonValue): String = Json.sha256(value).removePrefix("sha256:")
}
