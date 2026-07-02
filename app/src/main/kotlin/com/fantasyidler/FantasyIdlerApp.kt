package com.fantasyidler

import android.app.Application
import com.fantasyidler.notification.SessionNotificationManager
import com.fantasyidler.automation.LocalControlServer
import com.fantasyidler.automation.AgentGameBridge
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class FantasyIdlerApp : Application() {

    @Inject lateinit var notificationManager: SessionNotificationManager

  @Inject
  lateinit var agentGameBridge: AgentGameBridge

    override fun onCreate() {
        super.onCreate()
        notificationManager.createChannels()
  LocalControlServer.start(this, agentGameBridge)
    }
}
