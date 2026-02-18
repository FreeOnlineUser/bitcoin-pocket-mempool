package com.pocketmempool

import android.app.Application
import com.pocketmempool.notification.TransactionNotificationManager

/**
 * Application class for Pocket Mempool
 */
class PocketMempoolApplication : Application() {
    
    override fun onCreate() {
        super.onCreate()
        
        // Initialize notification channels
        TransactionNotificationManager(this)
    }
}