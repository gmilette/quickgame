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
                applicationId = "1:1047416669336:android:f80c551e899bd84804fced",
                apiKey = "AIzaSyDKvame0OtvpTAzn7xwCsCYLci2IuEaNjQ",
                projectId = "quickgame-336e4"
            )
        )
        client = FirebaseClient.create(
            useEmulators = false,
            emulatorHost = BuildConfig.EMULATOR_HOST
        )
    }
}
