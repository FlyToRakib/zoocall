package app.zoocall.core.app

import org.koin.core.module.Module
import org.koin.dsl.module

/** Koin wiring for the shared core. Platform shells pass their [Platform] adapters. */
fun coreModule(platform: Platform): Module = module {
    single { platform }
    single { ZoocallCore(get()) }
}
