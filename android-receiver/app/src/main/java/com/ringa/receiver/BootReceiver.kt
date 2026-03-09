package com.ringa.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            Intent.ACTION_USER_UNLOCKED,
            ReceiverService.ACTION_RESTART -> {
                ReceiverState.log("[${nowString()}] Boot/package event: ${intent.action}")
                ReceiverService.start(context)
            }
        }
    }
}
