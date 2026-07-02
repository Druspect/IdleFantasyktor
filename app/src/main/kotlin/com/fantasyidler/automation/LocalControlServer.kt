package com.fantasyidler.automation

import android.content.Context
import android.os.BatteryManager
import android.provider.Settings
import io.ktor.http.ContentType
import io.ktor.server.application.call
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.cio.CIO
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing

object LocalControlServer {
    @Volatile
    private var server: EmbeddedServer<*, *>? = null

    fun start(context: Context) {
        if (server != null) return

        val appContext = context.applicationContext

        server = embeddedServer(
            factory = CIO,
            port = 8080,
            host = "0.0.0.0"
        ) {
            routing {
                get("/api/v1/status") {
                    val androidId = Settings.Secure.getString(
                        appContext.contentResolver,
                        Settings.Secure.ANDROID_ID
                    ) ?: "unknown"

                    val battery = getBatteryPercent(appContext)

                    val json = """
                        {
                          "device_id": "idlefantasy_$androidId",
                          "status": "idle",
                          "battery": $battery,
                          "stats": {
                            "Mining": 1,
                            "Smithing": 1,
                            "Woodcutting": 1,
                            "Fishing": 1
                          }
                        }
                    """.trimIndent()

                    call.respondText(json, ContentType.Application.Json)
                }

                post("/api/v1/session/start") {
                    val body = call.receiveText()

                    val json = """
                        {
                          "result": "accepted",
                          "received": ${body.length}
                        }
                    """.trimIndent()

                    call.respondText(json, ContentType.Application.Json)
                }

                get("/") {
                    call.respondText("Idle Fantasy local control server online")
                }
            }
        }.start(wait = false)
    }

    private fun getBatteryPercent(context: Context): Int {
        return try {
            val batteryManager = context.getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            batteryManager.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        } catch (_: Exception) {
            100
        }
    }
}
