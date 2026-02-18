package com.pocketmempool.mempool

data class ProjectedBlockInfo(
    val index: Int,
    val transactionCount: Int,
    val totalWeight: Int,
    val totalFees: Double, // in BTC
    val minFeeRate: Double, // sat/vB
    val maxFeeRate: Double, // sat/vB
    val medianFeeRate: Double, // sat/vB
    val feeRateBands: List<FeeRateBand> // for color segments within block
)

data class FeeRateBand(
    val feeRateRange: ClosedFloatingPointRange<Double>, // sat/vB range
    val proportion: Float // 0.0 to 1.0, how much of block this band occupies (by weight)
)

data class LatestBlockInfo(
    val height: Int,
    val hash: String,
    val time: Long, // unix timestamp
    val txCount: Int,
    val size: Int,
    val weight: Int
)
