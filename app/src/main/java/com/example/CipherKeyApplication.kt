package com.example

import android.app.Application
import com.example.crypto.KeystoreCryptoEngine
import com.example.data.local.PreferencesRepository
import com.example.data.repository.VaultRepository
import com.example.features.TranslationEngine
import com.example.features.UpdateManager

class AppContainer(application: Application) {
    val cryptoEngine = KeystoreCryptoEngine()
    val preferencesRepository = PreferencesRepository(application)
    val vaultRepository = VaultRepository(application)
    val translationEngine = TranslationEngine(preferencesRepository)
    val updateManager = UpdateManager(application, preferencesRepository)
}

class CipherKeyApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
