package net.catenax.core.managedidentitywallets.plugins

import io.bkbn.kompendium.auth.configuration.JwtAuthConfiguration

import io.ktor.application.*
import io.ktor.auth.*
import io.ktor.auth.jwt.*
import io.ktor.client.*
import io.ktor.response.*
import io.ktor.client.engine.apache.*
import io.ktor.http.*
import io.ktor.sessions.*

import com.auth0.jwt.*
import com.auth0.jwk.UrlJwkProvider
import com.auth0.jwk.*
import com.auth0.jwt.algorithms.*
import java.security.interfaces.*

import java.net.URL

data class UserSession(val token: String) : Principal

object AuthConstants {
    const val RESOURCE_ACCESS = "resource_access"
    const val ROLES = "roles"
    const val ROLE_CREATE_WALLETS = "create_wallets"
    const val ROLE_UPDATE_WALLETS = "update_wallets"
    const val ROLE_VIEW_WALLETS = "view_wallets"
    const val ROLE_DELETE_WALLETS = "delete_wallets"
    const val CONFIG_VIEW = "auth-view"
    const val CONFIG_CREATE = "auth-create"
    const val CONFIG_UPDATE = "auth-update"
    const val CONFIG_DELETE = "auth-delete"

    val JWT_AUTH_CREATE = object : JwtAuthConfiguration {
        override val name: String = CONFIG_CREATE
    }
    val JWT_AUTH_VIEW = object : JwtAuthConfiguration {
        override val name: String = CONFIG_VIEW
    }
    val JWT_AUTH_UPDATE = object : JwtAuthConfiguration {
        override val name: String = CONFIG_UPDATE
    }
    val JWT_AUTH_DELETE = object : JwtAuthConfiguration {
        override val name: String = CONFIG_DELETE
    }
}

fun Application.configureSecurity() {

    val jwksUrl = environment.config.property("auth.jwksUrl").getString()
    val issuerUrl = environment.config.property("auth.issuerUrl").getString()
    val jwkRealm = environment.config.property("auth.realm").getString()
    val roleMappings = environment.config.property("auth.roleMappings").getString()
    val resourceId = environment.config.property("auth.resourceId").getString()
    val clientId = environment.config.property("auth.clientId").getString()
    val clientSecret = environment.config.property("auth.clientSecret").getString()
    val redirectUrl = environment.config.property("auth.redirectUrl").getString()
    val jwkProvider = UrlJwkProvider(URL(jwksUrl))

    val roleMap = roleMappings.split(",").map { it.split(":").get(0) to it.split(":").get(1) }.toMap()

    val oauthProvider = OAuthServerSettings.OAuth2ServerSettings(
        name = "keycloak",
        authorizeUrl = "$issuerUrl/protocol/openid-connect/auth",
        accessTokenUrl = "$issuerUrl/protocol/openid-connect/token",
        clientId = clientId,
        clientSecret = clientSecret,
        accessTokenRequiresBasicAuth = false,
        requestMethod = HttpMethod.Post, // must POST to token endpoint
        defaultScopes = listOf(AuthConstants.ROLES)
    )

    fun verify(credentials: JWTCredential, mappedRole: String): JWTPrincipal? {
        log.debug("Verifying " + credentials.payload.claims + " with mapped role $mappedRole")
        if (credentials.payload.claims != null && credentials.payload.claims.contains(AuthConstants.RESOURCE_ACCESS)) {
            val clientResources = credentials.payload.claims.get(AuthConstants.RESOURCE_ACCESS)!!.asMap().get(resourceId)
            if (clientResources != null && clientResources is Map<*, *> && clientResources.contains(AuthConstants.ROLES)) {
                val roles = clientResources.get(AuthConstants.ROLES)
                if (roles != null && roles is List<*> && roles.contains(mappedRole))
                    return JWTPrincipal(credentials.payload)
                else {
                    log.warn("Authentication information incomplete: missing role $mappedRole")
                    return null
                }
            } else {
                log.warn("Authentication information incomplete: missing ${AuthConstants.ROLES} for $resourceId")
                return null
            }
        } else {
            log.warn("Authentication information incomplete: missing ${AuthConstants.RESOURCE_ACCESS}")
            return null
        }
    }

    install(Sessions) {
        cookie<UserSession>("user_session")
        // potentially header<UserSession>("user_session")
    }

    install(Authentication) {

        // for the ui use oauth
        oauth("auth-ui") {
            client = HttpClient(Apache)
            providerLookup = { oauthProvider }
            urlProvider = {
                redirectUrl
            }
        }

        session<UserSession>("auth-ui-session") {
            validate {
                try {
                    val decoded = JWT.decode(it.token)
                    val kid = decoded.keyId
                    val jwk = jwkProvider.get(kid)
                    val algorithm = Algorithm.RSA256(jwk.getPublicKey() as RSAPublicKey, null)
                    val verifier = JWT.require(algorithm).withIssuer(issuerUrl).build()
                    verifier.verify(it.token)
                    it
                } catch (ex:Exception) {
                    log.warn("Authentication information validation error: " + ex.message)
                    null
                }
            }
            challenge {
                call.sessions.clear<UserSession>()
                call.respondRedirect("/login")
            }
        }

        // verify that all mappings are there
        if (roleMap.get(AuthConstants.ROLE_VIEW_WALLETS) == null) {
            log.error("Configuration error, ${AuthConstants.ROLE_VIEW_WALLETS} role mapping not defined, system will not behave correctly!")
            throw Exception("Configuration error, ${AuthConstants.ROLE_VIEW_WALLETS} role mapping not defined, system will not behave correctly!")
        }
        if (roleMap.get(AuthConstants.ROLE_CREATE_WALLETS) == null) {
            log.error("Configuration error, ${AuthConstants.ROLE_CREATE_WALLETS} role mapping not defined, system will not behave correctly!")
            throw Exception("Configuration error, ${AuthConstants.ROLE_CREATE_WALLETS} role mapping not defined, system will not behave correctly!")
        }
        if (roleMap.get(AuthConstants.ROLE_UPDATE_WALLETS) == null) {
            log.error("Configuration error, ${AuthConstants.ROLE_UPDATE_WALLETS} role mapping not defined, system will not behave correctly!")
            throw Exception("Configuration error, ${AuthConstants.ROLE_UPDATE_WALLETS} role mapping not defined, system will not behave correctly!")
        }
        if (roleMap.get(AuthConstants.ROLE_DELETE_WALLETS) == null) {
            log.error("Configuration error, ${AuthConstants.ROLE_DELETE_WALLETS} role mapping not defined, system will not behave correctly!")
            throw Exception("Configuration error, ${AuthConstants.ROLE_DELETE_WALLETS} role mapping not defined, system will not behave correctly!")
        }

        // for the API use JWT validation 
        jwt(AuthConstants.CONFIG_VIEW) {
            verifier(jwkProvider, issuerUrl)
            realm = jwkRealm
            validate {
                credentials -> verify(credentials, roleMap.get(AuthConstants.ROLE_VIEW_WALLETS)!!)
            }
        }

        jwt(AuthConstants.CONFIG_CREATE) {
            verifier(jwkProvider, issuerUrl)
            realm = jwkRealm
            validate {
                credentials -> verify(credentials, roleMap.get(AuthConstants.ROLE_CREATE_WALLETS)!!)
            }
        }

        jwt(AuthConstants.CONFIG_UPDATE) {
            verifier(jwkProvider, issuerUrl)
            realm = jwkRealm
            validate {
                credentials -> verify(credentials, roleMap.get(AuthConstants.ROLE_UPDATE_WALLETS)!!)
            }
        }

        jwt(AuthConstants.CONFIG_DELETE) {
            verifier(jwkProvider, issuerUrl)
            realm = jwkRealm
            validate {
                credentials -> verify(credentials, roleMap.get(AuthConstants.ROLE_DELETE_WALLETS)!!)
            }
        }

    }
}