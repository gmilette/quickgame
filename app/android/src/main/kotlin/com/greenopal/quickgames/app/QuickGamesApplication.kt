package com.greenopal.quickgames.app

import android.app.Application
import com.greenopal.quickgames.core.firebase.FirebaseClient
import com.greenopal.quickgames.core.model.PlatformClient
import dev.gitlive.firebase.Firebase
import dev.gitlive.firebase.FirebaseOptions
import dev.gitlive.firebase.initialize

class QuickGamesApplication : Application() {

    lateinit var client: PlatformClient
        private set

    override fun onCreate() {
        super.onCreate()
        Firebase.initialize(
            this,
            FirebaseOptions(
                applicationId = "1:000000000000:android:quickgames",
                apiKey = "fake-emulator-api-key",
                projectId = "demo-quickgames"
            )
        )
        client = FirebaseClient.create(
            useEmulators = true,
            emulatorHost = BuildConfig.EMULATOR_HOST
        )
    }
}
