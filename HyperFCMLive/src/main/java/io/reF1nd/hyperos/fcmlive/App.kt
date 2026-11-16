package io.reF1nd.hyperos.fcmlive

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import coil3.ImageLoader
import coil3.SingletonImageLoader
import io.github.libxposed.service.XposedService
import io.github.libxposed.service.XposedServiceHelper
import java.util.concurrent.CopyOnWriteArraySet

class App : Application(), SingletonImageLoader.Factory, XposedServiceHelper.OnServiceListener {
    fun interface ServiceListener {
        fun onServiceChanged(service: XposedService?)
    }

    companion object {
        @Volatile
        var xposedService: XposedService? = null
            private set

        private val mainHandler = Handler(Looper.getMainLooper())
        private val listeners = CopyOnWriteArraySet<ServiceListener>()

        fun addServiceListener(listener: ServiceListener) {
            listeners.add(listener)
            dispatch(listener, xposedService)
        }

        fun removeServiceListener(listener: ServiceListener) {
            listeners.remove(listener)
        }

        private fun notifyListeners(service: XposedService?) {
            listeners.forEach { dispatch(it, service) }
        }

        private fun dispatch(listener: ServiceListener, service: XposedService?) {
            if (Looper.myLooper() == Looper.getMainLooper()) {
                listener.onServiceChanged(service)
            } else {
                mainHandler.post { listener.onServiceChanged(service) }
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        XposedServiceHelper.registerListener(this)
    }

    override fun onServiceBind(service: XposedService) {
        xposedService = service
        notifyListeners(service)
    }

    override fun onServiceDied(service: XposedService) {
        if (xposedService === service) {
            xposedService = null
        }
        notifyListeners(xposedService)
    }

    override fun newImageLoader(context: Context): ImageLoader {
        val iconSize = context.resources.getDimensionPixelSize(R.dimen.app_icon_size)
        return ImageLoader.Builder(context)
            .components {
                add(AppIconKeyer())
                add(AppIconFetcher.Factory(iconSize))
            }
            .build()
    }
}
