package com.pocketmempool.mempool

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.pocketmempool.rpc.BitcoinRpcClient
import com.pocketmempool.rpc.RpcConfig
import com.pocketmempool.rpc.RpcConfigDefaults
import com.pocketmempool.rpc.RpcException
import com.pocketmempool.storage.WatchListManager
import com.pocketmempool.notification.TransactionNotificationManager
import com.pocketmempool.widget.MempoolWidgetProvider
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.*
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Service that manages mempool data and GBT projections
 */
private val lenientJson = Json { ignoreUnknownKeys = true }

class MempoolService : Service() {
    companion object {
        private const val TAG = "MempoolService"
        private const val POLL_INTERVAL_MS = 10_000L // 10 seconds
        private const val MAX_BLOCK_WEIGHT = 4_000_000
        private const val MAX_BLOCKS = 8
    }

    private val binder = MempoolBinder()
    private var serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var pollingJob: Job? = null
    
    // Bitcoin RPC client
    private lateinit var rpcClient: BitcoinRpcClient
    private var isRpcConnected = false
    
    // Watch list manager
    private lateinit var watchListManager: WatchListManager
    
    // Notification manager
    private lateinit var notificationManager: TransactionNotificationManager
    
    // GBT generator
    private var gbtGenerator: GbtGenerator? = null
    
    // Current mempool state
    private val currentMempool = ConcurrentHashMap<String, MempoolEntry>()
    private val txIdToUid = ConcurrentHashMap<String, Int>()
    private val uidToTxId = ConcurrentHashMap<Int, String>()
    private val uidCounter = AtomicInteger(1)
    
    // Note: Watched transactions now managed by WatchListManager
    
    // StateFlow for UI updates
    private val _mempoolState = MutableStateFlow(MempoolState())
    val mempoolState: StateFlow<MempoolState> = _mempoolState.asStateFlow()
    
    private val _gbtResult = MutableStateFlow<GbtResult?>(null)
    val gbtResult: StateFlow<GbtResult?> = _gbtResult.asStateFlow()
    
    // Fee rate histogram data
    private val _feeRateHistogram = MutableStateFlow<Map<Int, Int>>(emptyMap())
    val feeRateHistogram: StateFlow<Map<Int, Int>> = _feeRateHistogram.asStateFlow()
    
    // Fee estimates from estimatesmartfee
    private val _feeEstimates = MutableStateFlow(FeeEstimates())
    val feeEstimates: StateFlow<FeeEstimates> = _feeEstimates.asStateFlow()
    
    // Projected block info with fee rate data
    private val _projectedBlocks = MutableStateFlow<List<ProjectedBlockInfo>>(emptyList())
    val projectedBlocks: StateFlow<List<ProjectedBlockInfo>> = _projectedBlocks.asStateFlow()

    // Latest mined block info
    private val _latestBlock = MutableStateFlow<LatestBlockInfo?>(null)
    val latestBlock: StateFlow<LatestBlockInfo?> = _latestBlock.asStateFlow()

    // RPC connection status
    private val _rpcStatus = MutableStateFlow(RpcStatus.DISCONNECTED)
    val rpcStatus: StateFlow<RpcStatus> = _rpcStatus.asStateFlow()

    fun reinitializeRpcClient() {
        Log.d(TAG, "Reinitializing RPC client from connection preferences")
        isRpcConnected = false
        _rpcStatus.value = RpcStatus.DISCONNECTED
        currentMempool.clear()
        txIdToUid.clear()
        uidToTxId.clear()
        uidCounter.set(1)
        _gbtResult.value = null
        initializeRpcClient()
    }

    inner class MempoolBinder : Binder() {
        fun getService(): MempoolService = this@MempoolService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "MempoolService created")
        initializeRpcClient()
        initializeWatchListManager()
        initializeNotificationManager()
        initializeGbtGenerator()
        startPolling()
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.d(TAG, "MempoolService destroyed")
        stopPolling()
        gbtGenerator?.destroy()
        serviceScope.cancel()
    }

    private fun initializeRpcClient() {
        // Load from ConnectionPreferences (set by the connection settings screen)
        val connPrefs = com.pocketmempool.storage.ConnectionPreferences(this)
        if (connPrefs.isConfigured()) {
            rpcClient = BitcoinRpcClient(
                rpcHost = connPrefs.getHost(),
                rpcPort = connPrefs.getPort(),
                rpcUser = connPrefs.getUsername(),
                rpcPassword = connPrefs.getPassword()
            )
            Log.d(TAG, "RPC client initialized for ${connPrefs.getHost()}:${connPrefs.getPort()}")
        } else {
            // Fallback to RpcConfig defaults
            RpcConfigDefaults.initializeDefaultsIfNeeded(this)
            val config = RpcConfig.load(this)
            rpcClient = BitcoinRpcClient(
                rpcHost = config.host,
                rpcPort = config.port,
                rpcUser = config.username,
                rpcPassword = config.password
            )
            Log.d(TAG, "RPC client initialized from defaults for ${config.host}:${config.port}")
        }
    }
    
    private fun initializeWatchListManager() {
        watchListManager = WatchListManager(this)
        Log.d(TAG, "Watch list manager initialized")
    }
    
    private fun initializeNotificationManager() {
        notificationManager = TransactionNotificationManager(this)
        Log.d(TAG, "Notification manager initialized")
    }

    private fun initializeGbtGenerator() {
        gbtGenerator = GbtGenerator.create(MAX_BLOCK_WEIGHT, MAX_BLOCKS)
        Log.d(TAG, "GBT generator initialized")
    }

    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = serviceScope.launch {
            while (isActive) {
                try {
                    updateMempoolData()
                    delay(POLL_INTERVAL_MS)
                } catch (e: Exception) {
                    Log.e(TAG, "Error updating mempool data", e)
                    delay(POLL_INTERVAL_MS) // Continue polling even on error
                }
            }
        }
        Log.d(TAG, "Started mempool polling")
    }

    private fun stopPolling() {
        pollingJob?.cancel()
        pollingJob = null
        Log.d(TAG, "Stopped mempool polling")
    }

    private suspend fun updateMempoolData() {
        try {
            // First check RPC connection
            if (!isRpcConnected) {
                val pingResult = testRpcConnection()
                if (!pingResult) {
                    _rpcStatus.value = RpcStatus.DISCONNECTED
                    return
                }
                isRpcConnected = true
                _rpcStatus.value = RpcStatus.CONNECTED
            }
            
            // Get mempool data and info in parallel
            val (newMempoolData, mempoolInfo, feeEstimates) = coroutineScope {
                val mempoolDataDeferred = async { getRawMempool() }
                val mempoolInfoDeferred = async { getMempoolInfo() }
                val feeEstimatesDeferred = async { getFeeEstimates() }
                Triple(
                    mempoolDataDeferred.await(),
                    mempoolInfoDeferred.await(),
                    feeEstimatesDeferred.await()
                )
            }
            
            if (newMempoolData == null) return
            
            // Update fee estimates
            _feeEstimates.value = feeEstimates
            
            // Find new and removed transactions
            val newTxIds = newMempoolData.keys.toSet()
            val currentTxIds = currentMempool.keys.toSet()
            
            val addedTxIds = newTxIds.minus(currentTxIds.toSet())
            val removedTxIds = currentTxIds.minus(newTxIds.toSet())
            
            // Update current mempool
            removedTxIds.forEach { txId ->
                currentMempool.remove(txId)
                val uid = txIdToUid.remove(txId)
                uid?.let { uidToTxId.remove(it) }
            }
            
            addedTxIds.forEach { txId ->
                newMempoolData[txId]?.let { entry ->
                    currentMempool[txId] = entry
                    val uid = uidCounter.getAndIncrement()
                    txIdToUid[txId] = uid
                    uidToTxId[uid] = txId
                }
            }
            
            // Update mempool state with real data from mempoolinfo
            _mempoolState.value = MempoolState(
                transactionCount = mempoolInfo?.size ?: currentMempool.size,
                totalVbytes = mempoolInfo?.bytes ?: currentMempool.values.sumOf { it.vsize },
                totalFees = currentMempool.values.sumOf { it.fee },
                vbytesPerSecond = 0.0 // TODO: Calculate from historical data
            )
            
            // Update fee rate histogram
            updateFeeRateHistogram()
            
            // Run GBT if we have mempool data
            if (currentMempool.isNotEmpty()) {
                runGbtAlgorithm(addedTxIds, removedTxIds)
            }
            
            // Fetch latest mined block
            fetchLatestBlock()
            
            // Check watched transactions for confirmations
            checkWatchedTransactions()
            
            // Update home screen widget
            updateWidget()
            
        } catch (e: RpcException) {
            Log.e(TAG, "RPC error in updateMempoolData", e)
            isRpcConnected = false
            _rpcStatus.value = RpcStatus.ERROR
        } catch (e: Exception) {
            Log.e(TAG, "Error in updateMempoolData", e)
        }
    }

    private suspend fun testRpcConnection(): Boolean {
        return try {
            rpcClient.ping()
        } catch (e: Exception) {
            Log.w(TAG, "RPC connection test failed", e)
            false
        }
    }

    private suspend fun getRawMempool(): Map<String, MempoolEntry>? {
        return try {
            val json = rpcClient.getRawMempool()
            val result = mutableMapOf<String, MempoolEntry>()
            
            json.forEach { (txid, entryJson) ->
                try {
                    val entry = lenientJson.decodeFromJsonElement(MempoolEntry.serializer(), entryJson)
                    result[txid] = entry
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to parse mempool entry for $txid", e)
                }
            }
            
            result
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get raw mempool", e)
            null
        }
    }
    
    private suspend fun getMempoolInfo(): MempoolInfo? {
        return try {
            val json = rpcClient.getMempoolInfo()
            MempoolInfo(
                size = json["size"]?.jsonPrimitive?.int ?: 0,
                bytes = json["bytes"]?.jsonPrimitive?.int ?: 0,
                usage = json["usage"]?.jsonPrimitive?.long ?: 0L,
                maxmempool = json["maxmempool"]?.jsonPrimitive?.long ?: 0L,
                mempoolminfee = json["mempoolminfee"]?.jsonPrimitive?.double ?: 0.0,
                minrelaytxfee = json["minrelaytxfee"]?.jsonPrimitive?.double ?: 0.0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mempool info", e)
            null
        }
    }
    
    private suspend fun getFeeEstimates(): FeeEstimates {
        return try {
            val estimates1 = rpcClient.estimateSmartFee(1)
            val estimates3 = rpcClient.estimateSmartFee(3)
            val estimates6 = rpcClient.estimateSmartFee(6)
            
            FeeEstimates(
                fastestFee = estimates1["feerate"]?.jsonPrimitive?.double?.let { (it * 100_000_000).toInt() } ?: 0,
                halfHourFee = estimates3["feerate"]?.jsonPrimitive?.double?.let { (it * 100_000_000).toInt() } ?: 0,
                hourFee = estimates6["feerate"]?.jsonPrimitive?.double?.let { (it * 100_000_000).toInt() } ?: 0
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get fee estimates", e)
            FeeEstimates()
        }
    }

    private fun runGbtAlgorithm(addedTxIds: Set<String>, removedTxIds: Set<String>) {
        try {
            val generator = gbtGenerator ?: return
            
            if (addedTxIds.isEmpty() && removedTxIds.isEmpty()) {
                // No changes, skip GBT run
                return
            }
            
            val maxUid = uidCounter.get()
            
            if (removedTxIds.isNotEmpty()) {
                // Use update method for incremental changes
                val newThreadTxs = addedTxIds.mapNotNull { txId ->
                    currentMempool[txId]?.let { entry ->
                        convertToThreadTransaction(txId, entry)
                    }
                }
                
                val removedUids = removedTxIds.mapNotNull { txIdToUid[it] }
                
                val result = generator.update(
                    newTxs = newThreadTxs,
                    removeTxs = removedUids,
                    maxUid = maxUid
                )
                
                _gbtResult.value = result
                result?.let { computeProjectedBlockInfo(it) }
                Log.d(TAG, "GBT update completed: ${result?.blocks?.size} blocks projected")
                
            } else {
                // Full rebuild
                val allThreadTxs = currentMempool.entries.mapNotNull { (txId, entry) ->
                    convertToThreadTransaction(txId, entry)
                }
                
                val result = generator.make(
                    mempool = allThreadTxs,
                    maxUid = maxUid
                )
                
                _gbtResult.value = result
                result?.let { computeProjectedBlockInfo(it) }
                Log.d(TAG, "GBT full run completed: ${result?.blocks?.size} blocks projected")
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error running GBT algorithm", e)
        }
    }

    private fun computeProjectedBlockInfo(gbtResult: GbtResult) {
        try {
            val blockInfos = gbtResult.blocks.mapIndexed { index, block ->
                val feeRates = mutableListOf<Double>()
                val weights = mutableListOf<Int>()
                var totalFees = 0.0
                var totalWeight = gbtResult.blockWeights.getOrNull(index) ?: 0

                for (uid in block) {
                    val txId = uidToTxId[uid] ?: continue
                    val entry = currentMempool[txId] ?: continue
                    val feeRate = entry.effectiveFee / entry.vsize.toDouble() * 100_000_000.0 // sat/vB
                    feeRates.add(feeRate)
                    weights.add(entry.weight)
                    totalFees += entry.effectiveFee
                }

                val sortedRates = feeRates.sorted()
                val minFeeRate = sortedRates.firstOrNull() ?: 0.0
                val maxFeeRate = sortedRates.lastOrNull() ?: 0.0
                val medianFeeRate = if (sortedRates.isNotEmpty()) {
                    val mid = sortedRates.size / 2
                    if (sortedRates.size % 2 == 0) (sortedRates[mid - 1] + sortedRates[mid]) / 2.0
                    else sortedRates[mid]
                } else 0.0

                // Compute fee rate bands
                data class BandDef(val range: ClosedFloatingPointRange<Double>, val label: String)
                val bandDefs = listOf(
                    BandDef(0.0..2.0, "magenta"),
                    BandDef(2.0..4.0, "purple"),
                    BandDef(4.0..10.0, "blue"),
                    BandDef(10.0..20.0, "green"),
                    BandDef(20.0..50.0, "yellow"),
                    BandDef(50.0..100.0, "orange"),
                    BandDef(100.0..Double.MAX_VALUE, "red")
                )

                val totalWeightForBands = weights.sum().toFloat().coerceAtLeast(1f)
                val bands = bandDefs.mapNotNull { bandDef ->
                    var bandWeight = 0
                    for (i in feeRates.indices) {
                        if (feeRates[i] >= bandDef.range.start && (feeRates[i] < bandDef.range.endInclusive || bandDef.range.endInclusive == Double.MAX_VALUE)) {
                            bandWeight += weights[i]
                        }
                    }
                    if (bandWeight > 0) {
                        FeeRateBand(
                            feeRateRange = bandDef.range,
                            proportion = bandWeight / totalWeightForBands
                        )
                    } else null
                }

                ProjectedBlockInfo(
                    index = index,
                    transactionCount = block.size,
                    totalWeight = totalWeight,
                    totalFees = totalFees,
                    minFeeRate = minFeeRate,
                    maxFeeRate = maxFeeRate,
                    medianFeeRate = medianFeeRate,
                    feeRateBands = bands
                )
            }
            _projectedBlocks.value = blockInfos
        } catch (e: Exception) {
            Log.e(TAG, "Error computing projected block info", e)
        }
    }

    private suspend fun fetchLatestBlock() {
        try {
            val blockHash = rpcClient.getBestBlockHash()
            // Only update if hash changed
            if (_latestBlock.value?.hash == blockHash) return
            
            val blockJson = rpcClient.getBlock(blockHash, 1).jsonObject
            _latestBlock.value = LatestBlockInfo(
                height = blockJson["height"]?.jsonPrimitive?.int ?: 0,
                hash = blockHash,
                time = blockJson["time"]?.jsonPrimitive?.long ?: 0L,
                txCount = blockJson["nTx"]?.jsonPrimitive?.int ?: blockJson["tx"]?.jsonArray?.size ?: 0,
                size = blockJson["size"]?.jsonPrimitive?.int ?: 0,
                weight = blockJson["weight"]?.jsonPrimitive?.int ?: 0
            )
            Log.d(TAG, "Latest block updated: ${_latestBlock.value?.height}")
        } catch (e: Exception) {
            Log.e(TAG, "Error fetching latest block", e)
        }
    }

    private fun convertToThreadTransaction(txId: String, entry: MempoolEntry): ThreadTransaction? {
        val uid = txIdToUid[txId] ?: return null
        
        // Convert depends (parent txids) to UIDs
        val inputUids = entry.depends.mapNotNull { parentTxId ->
            txIdToUid[parentTxId]
        }.toIntArray()
        
        // Calculate effective fee per vsize
        val effectiveFeePerVsize = entry.effectiveFee / entry.vsize.toDouble()
        
        // Use time as order (later transactions have higher order)
        val order = (entry.time and 0xFFFFFFFF).toInt()
        
        return ThreadTransaction(
            uid = uid,
            order = order,
            fee = entry.effectiveFee,
            weight = entry.weight,
            sigops = 0, // TODO: Get actual sigops count from RPC if available
            effectiveFeePerVsize = effectiveFeePerVsize,
            inputs = inputUids
        )
    }

    private fun updateFeeRateHistogram() {
        val histogram = mutableMapOf<Int, Int>()
        
        // Group transactions by fee rate ranges (sat/vB)
        currentMempool.values.forEach { entry ->
            val feeRate = (entry.effectiveFee / entry.vsize * 100_000_000).toInt() // Convert to sat/vB
            val bucket = when {
                feeRate <= 2 -> 1
                feeRate <= 4 -> 3
                feeRate <= 10 -> 5
                feeRate <= 20 -> 10
                feeRate <= 50 -> 20
                feeRate <= 100 -> 50
                else -> 100
            }
            histogram[bucket] = (histogram[bucket] ?: 0) + 1
        }
        
        _feeRateHistogram.value = histogram
    }

    private suspend fun checkWatchedTransactions() {
        val watchedTxIds = watchListManager.getWatchedTransactionIds()
        if (watchedTxIds.isEmpty()) return
        
        try {
            // Check which watched transactions are no longer in mempool
            val confirmedTxs = mutableListOf<String>()
            
            watchedTxIds.forEach { txid ->
                if (!currentMempool.containsKey(txid)) {
                    // Transaction is no longer in mempool, might be confirmed
                    try {
                        val txDetails = rpcClient.getRawTransaction(txid, true)
                        val confirmations = txDetails.jsonObject["confirmations"]?.jsonPrimitive?.int ?: 0
                        if (confirmations > 0) {
                            val blockHeight = txDetails.jsonObject["blockheight"]?.jsonPrimitive?.int ?: 0
                            confirmedTxs.add(txid)
                            
                            // Send notification
                            notificationManager.notifyTransactionConfirmed(
                                txid = txid,
                                blockNumber = blockHeight,
                                confirmations = confirmations
                            )
                            
                            Log.d(TAG, "Watched transaction $txid confirmed in block $blockHeight with $confirmations confirmations")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to check confirmation for watched tx $txid", e)
                    }
                }
            }
            
            // Clean up confirmed transactions from watch list
            confirmedTxs.forEach { txid ->
                watchListManager.removeTransaction(txid)
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error checking watched transactions", e)
        }
    }

    /**
     * Add a transaction to the watch list for confirmation tracking
     */
    fun watchTransaction(txId: String) {
        watchListManager.addTransaction(txId)
        Log.d(TAG, "Now watching transaction: $txId")
    }

    /**
     * Remove a transaction from the watch list
     */
    fun unwatchTransaction(txId: String) {
        watchListManager.removeTransaction(txId)
        Log.d(TAG, "Stopped watching transaction: $txId")
    }

    /**
     * Get list of currently watched transactions
     */
    fun getWatchedTransactions(): List<String> = watchListManager.getWatchedTransactionIds()
    
    /**
     * Check if a transaction is being watched
     */
    fun isWatched(txId: String): Boolean = watchListManager.isWatched(txId)

    /**
     * Enable/disable mempool polling based on UI visibility
     */
    fun setPollingEnabled(enabled: Boolean) {
        if (enabled && pollingJob?.isActive != true) {
            startPolling()
        } else if (!enabled && pollingJob?.isActive == true) {
            stopPolling()
        }
    }

    /**
     * Search for a transaction by ID
     */
    suspend fun searchTransaction(txid: String): TransactionSearchResult {
        return try {
            // First check if it's in current mempool
            currentMempool[txid]?.let { entry ->
                val uid = txIdToUid[txid]
                val blockPosition = findTransactionInProjectedBlocks(uid)
                
                return TransactionSearchResult.InMempool(
                    txid = txid,
                    entry = entry,
                    projectedBlockPosition = blockPosition
                )
            }
            
            // If not in mempool, try to get it via RPC
            val txDetails = rpcClient.getRawTransaction(txid, true).jsonObject
            val confirmations = txDetails["confirmations"]?.jsonPrimitive?.int ?: 0
            
            if (confirmations > 0) {
                TransactionSearchResult.Confirmed(
                    txid = txid,
                    confirmations = confirmations,
                    blockHash = txDetails["blockhash"]?.jsonPrimitive?.content
                )
            } else {
                TransactionSearchResult.NotFound
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error searching transaction $txid", e)
            TransactionSearchResult.Error("Search failed: ${e.message}")
        }
    }
    
    private fun findTransactionInProjectedBlocks(uid: Int?): Int? {
        if (uid == null) return null
        
        val currentGbt = _gbtResult.value ?: return null
        currentGbt.blocks.forEachIndexed { index, block ->
            if (block.contains(uid)) {
                return index
            }
        }
        return null
    }
    
    private fun updateWidget() {
        try {
            val mempoolState = _mempoolState.value
            val feeEstimates = _feeEstimates.value
            
            MempoolWidgetProvider.updateWidgetData(
                context = this,
                txCount = mempoolState.transactionCount,
                totalVmb = (mempoolState.totalVbytes / 1_000_000.0).toFloat(),
                nextBlockFee = feeEstimates.fastestFee
            )
        } catch (e: Exception) {
            Log.e(TAG, "Error updating widget", e)
        }
    }
}

/**
 * Current state of the mempool
 */
data class MempoolState(
    val transactionCount: Int = 0,
    val totalVbytes: Int = 0,
    val totalFees: Double = 0.0,
    val vbytesPerSecond: Double = 0.0, // Inflow rate
    val lastUpdated: Long = System.currentTimeMillis()
)

/**
 * Mempool info from getmempoolinfo RPC
 */
data class MempoolInfo(
    val size: Int,
    val bytes: Int,
    val usage: Long,
    val maxmempool: Long,
    val mempoolminfee: Double,
    val minrelaytxfee: Double
)

/**
 * Fee estimates from estimatesmartfee RPC
 */
data class FeeEstimates(
    val fastestFee: Int = 0, // sat/vB for next block
    val halfHourFee: Int = 0, // sat/vB for ~30 min
    val hourFee: Int = 0 // sat/vB for ~1 hour
)

/**
 * RPC connection status
 */
enum class RpcStatus {
    CONNECTED,
    DISCONNECTED,
    ERROR
}

/**
 * Transaction search result
 */
sealed class TransactionSearchResult {
    data class InMempool(
        val txid: String,
        val entry: MempoolEntry,
        val projectedBlockPosition: Int?
    ) : TransactionSearchResult()
    
    data class Confirmed(
        val txid: String,
        val confirmations: Int,
        val blockHash: String?
    ) : TransactionSearchResult()
    
    object NotFound : TransactionSearchResult()
    
    data class Error(val message: String) : TransactionSearchResult()
}