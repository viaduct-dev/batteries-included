package com.example.config

import org.koin.core.Koin
import viaduct.service.api.spi.TenantCodeInjector
import javax.inject.Provider
import kotlin.reflect.KClass

/**
 * Custom TenantCodeInjector that uses Koin for dependency injection
 * This allows Viaduct to resolve resolver instances using Koin
 *
 * @param koin The Koin instance to use for dependency resolution
 */
class KoinTenantCodeInjector(
    private val koin: Koin
) : TenantCodeInjector {
    @Suppress("UNCHECKED_CAST")
    override fun <T> getProvider(clazz: Class<T>): Provider<T> {
        return Provider {
            // Convert Java Class to Kotlin KClass and get instance from Koin
            val kClass = (clazz as Class<Any>).kotlin as KClass<Any>
            koin.get(kClass, null) as T
        }
    }
}
