package com.pocketmempool

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.pocketmempool.ui.mempool.MempoolScreen
import com.pocketmempool.ui.mempool.TransactionSearchScreen
import com.pocketmempool.ui.mempool.ConnectionSettingsScreen
import com.pocketmempool.ui.theme.PocketMempoolTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        
        setContent {
            PocketMempoolTheme {
                PocketMempoolApp()
            }
        }
    }
}

@Composable
fun PocketMempoolApp() {
    val navController = rememberNavController()
    
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background
    ) {
        NavHost(
            navController = navController,
            startDestination = "connection_settings"
        ) {
            composable("connection_settings") {
                ConnectionSettingsScreen(
                    onNavigateToMempool = {
                        navController.navigate("mempool") {
                            popUpTo("connection_settings") { inclusive = true }
                        }
                    }
                )
            }
            
            composable("mempool") {
                MempoolScreen(
                    onNavigateToTransactionSearch = {
                        navController.navigate("transaction_search")
                    },
                    onNavigateToSettings = {
                        navController.navigate("connection_settings")
                    }
                )
            }
            
            composable("transaction_search") {
                TransactionSearchScreen(
                    onNavigateBack = {
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}