package com.karyo.app.auth

import io.restassured.RestAssured.given
import io.restassured.response.Response

/**
 * Direct-access (password) grant against a Keycloak realm, shared by every test that boots
 * [KeycloakTestResource].
 *
 * Returns the raw [Response] rather than a status or a token so each caller extracts what it
 * cares about: [KeycloakEventPollerTest] asserts the status code (it only needs Keycloak to
 * record a LOGIN / LOGIN_ERROR admin event), while [RealmPrincipalKindClaimTest] decodes the
 * issued access token.
 *
 * [clientSecret] is null for public clients such as the master realm's `admin-cli`.
 */
object KeycloakTestGrants {

    fun passwordGrant(
        realmUrl: String,
        clientId: String,
        clientSecret: String?,
        username: String,
        password: String,
    ): Response =
        given()
            .baseUri(realmUrl.trimEnd('/'))
            .contentType("application/x-www-form-urlencoded")
            .formParam("grant_type", "password")
            .formParam("client_id", clientId)
            .apply { clientSecret?.let { formParam("client_secret", it) } }
            .formParam("username", username)
            .formParam("password", password)
            .post("/protocol/openid-connect/token")
}
