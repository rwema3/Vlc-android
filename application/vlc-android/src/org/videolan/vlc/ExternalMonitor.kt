/*
 * *************************************************************************
 *  NetworkMonitor.java
 * **************************************************************************
 *  Copyright © 2017 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *  ***************************************************************************
 */

package org.videolan.vlc

import android.annotation.SuppressLint
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.util.Log
import androidx.core.content.getSystemService
import androidx.lifecycle.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.BroadcastChannel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.Channel.Factory.BUFFERED
import kotlinx.coroutines.channels.actor
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.ACTION_CHECK_STORAGES
import org.videolan.resources.AppContextProvider
import org.videolan.resources.util.getFromMl
import org.videolan.resources.util.launchForeground
import org.videolan.resources.util.parcelable
import org.videolan.tools.*
import org.videolan.tools.livedata.LiveDataset
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.gui.helpers.hf.OtgAccess
import java.lang.ref.WeakReference

private const val TAG = "VLC/ExternalMonitor"

@SuppressLint("StaticFieldLeak")
object ExternalMonitor : BroadcastReceiver(), DefaultLifecycleObserver, CoroutineScope by MainScope() {

    private lateinit var ctx: Context
    private var registered = false

    @OptIn(ObsoleteCoroutinesApi::class)
    private val actor = actor<DeviceAction>(capacity = Channel.CONFLATED) {
        for (action in channel) when (action){
            is MediaMounted -> {
                if (action.uuid.isEmpty()) return@actor
                Log.i("ExternalMonitor", "Storage management: mount: ${action.uuid} - ${action.path}")
                val isNew = ctx.getFromMl {
                    val isNewForMl = !isDeviceKnown(action.uuid, action.path, true)
                    addDevice(action.uuid, action.path, true)
                    isNewForMl
                }
                if (isNew) notifyNewStorage(action)
            }
            is MediaUnmounted -> {
                delay(100L)
                Log.i("ExternalMonitor", "Storage management: unmount: ${action.uuid} - ${action.path}")
                Medialibrary.getInstance().removeDevice(action.uuid, action.path)
                storageChannel.trySend(action)
            }
        }
    }

    init {
        launch {
            ProcessLifecycleOwner.get().lifecycle.addObserver(this@ExternalMonitor)
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!this::ctx.isInitialized) ctx = context.applicationContext
        when (intent.action) {
            Intent.ACTION_MEDIA_MOUNTED -> intent.data?.let { actor.trySend(MediaMounted(it)) }
            Intent.ACTION_MEDIA_UNMOUNTED,
            Intent.ACTION_MEDIA_EJECT -> intent.data?.let {
                actor.trySend(MediaUnmounted(it))
            }
            UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                if (intent.hasExtra(UsbManager.EXTRA_DEVICE)) {
                    val device = intent.parcelable<UsbDevice>(UsbManager.EXTRA_DEVICE)
                    device?.let { devices.add(it) }
                }
            }
            UsbManager.ACTION_USB_DEVICE_DETACHED -> if (intent.hasExtra(UsbManager.EXTRA_DEVICE)) {
                OtgAccess.otgRoot.value = null
                val device = intent.parcelable<UsbDevice>(UsbManager.EXTRA_DEVICE)
                device?.let { devices.remove(it) }
            }
        }
    }

    @OptIn(ObsoleteCoroutinesApi::class)
    private val storageChannel = BroadcastChannel<DeviceAction>(BUFFERED)
    @OptIn(ObsoleteCoroutinesApi::class)
    val storageEvents : Flow<DeviceAction>
        get() = storageChannel.asFlow()
    private var storageObserver: WeakReference<Activity>? = null

    var devices = LiveDataset<UsbDevice>()

    override fun onStart(owner: LifecycleOwner) {
        if (registered) return
        val ctx = AppContextProvider.appContext
        val storageFilter = IntentFilter(Intent.ACTION_MEDIA_MOUNTED)
        val otgFilter = IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED)
        storageFilter.addAction(Intent.ACTION_MEDIA_UNMOUNTED)
        storageFilter.addAction(Intent.ACTION_MEDIA_EJECT)
        otgFilter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        storageFilter.addDataScheme("file")
        ctx.registerReceiver(this, storageFilter)
        ctx.registerReceiver(this, otgFilter)
        registered = true
        checkNewStorages(ctx)
    }

    private fun checkNewStorages(ctx: Context) {
        if (Medialibrary.getInstance().isStarted) {
            val scanOpt = if (Settings.showTvUi) ML_SCAN_ON
            else Settings.getInstance(ctx).getInt(KEY_MEDIALIBRARY_SCAN, -1)
            if (scanOpt == ML_SCAN_ON)
                AppScope.launch { ctx.launchForeground(Intent(ACTION_CHECK_STORAGES, null, ctx, MediaParsingService::class.java)) }
        }
        val usbManager = ctx.getSystemService<UsbManager>() ?: return
        devices.add(ArrayList(usbManager.deviceList.values))
    }

    override fun onStop(owner: LifecycleOwner) {
        val ctx = AppContextProvider.appContext
        if (registered) try {
            ctx.unregisterReceiver(this)
        } catch (iae: IllegalArgumentException) {}
        registered = false
        devices.clear()
    }

    @Synchronized
    @OptIn(ObsoleteCoroutinesApi::class)
    private fun notifyNewStorage(mediaMounted: MediaMounted) {
        val activity = storageObserver?.get() ?: return
        UiTools.newStorageDetected(activity, mediaMounted.path)
        storageChannel.trySend(mediaMounted)
    }

    @Synchronized
    fun subscribeStorageCb(observer: Activity) {
        storageObserver = WeakReference(observer)
    }

    @Synchronized
    fun unsubscribeStorageCb(observer: Activity) {
        if (storageObserver?.get() === observer) {
            storageObserver?.clear()
            storageObserver = null
        }
    }
}

fun containsDevice(devices: Array<String>, device: String): Boolean {
    if (devices.isNullOrEmpty()) return false
    for (dev in devices) if (device.startsWith(dev.removeFileScheme())) return true
    return false
}

sealed class DeviceAction
class MediaMounted(val uri : Uri, val path : String = uri.path!!, val uuid : String = uri.lastPathSegment!!) : DeviceAction()
class MediaUnmounted(val uri : Uri, val path : String = uri.path!!, val uuid : String = uri.lastPathSegment!!) : DeviceAction()
