package com.viaduct.config

import viaduct.service.api.spi.TenantCodeInjector
import javax.inject.Provider

/**
 * A TenantCodeInjector that delegates to a swappable underlying injector.
 *
 * Used for CRaC support: the Viaduct factory is created with this injector
 * before Koin is initialized (to capture expensive schema compilation in the
 * checkpoint). After restore, the delegate is set to a real KoinTenantCodeInjector
 * backed by a Koin instance with live environment configuration.
 *
 * The providers returned by [getProvider] resolve lazily through the delegate,
 * so they are safe to construct before the delegate is set — as long as
 * [Provider.get] is not called until after wiring.
 */
class DelegatingTenantCodeInjector : TenantCodeInjector {
    @Volatile
    var delegate: TenantCodeInjector? = null

    override fun <T> getProvider(clazz: Class<T>): Provider<T> {
        return Provider {
            val d = delegate
                ?: throw IllegalStateException(
                    "CRaC DelegatingTenantCodeInjector: delegate not set. " +
                    "Cannot resolve ${clazz.name}. " +
                    "Ensure the delegate is wired after CRaC restore."
                )
            d.getProvider(clazz).get()
        }
    }
}
