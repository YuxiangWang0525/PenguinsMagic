package me.riverfrozer.penguinsmagic

import android.app.Application
import me.riverfrozer.penguinsmagic.data.Repositories

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Repositories.init(this)
    }
}
