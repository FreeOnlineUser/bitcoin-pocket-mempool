package com.pocketmempool.mempool

import kotlinx.serialization.Serializable
import kotlinx.serialization.SerialName

/**
 * Represents a mempool transaction entry from getrawmempool RPC call
 * Compatible with both older (flat fee fields) and newer (fees object) Bitcoin Core versions
 */
@Serializable
data class MempoolEntry(
    val vsize: Int = 0,
    val weight: Int = 0,
    val fee: Double = 0.0,
    val modifiedfee: Double = 0.0,
    val time: Long = 0,
    val height: Int = 0,
    val descendantcount: Int = 0,
    val descendantsize: Int = 0,
    val descendantfees: Double = 0.0,
    val ancestorcount: Int = 0,
    val ancestorsize: Int = 0,
    val ancestorfees: Double = 0.0,
    val wtxid: String = "",
    val fees: MempoolFees? = null,
    val depends: List<String> = emptyList(),
    val spentby: List<String> = emptyList(),
    val unbroadcast: Boolean = false
) {
    /** Get the base fee, preferring the fees object if available */
    val effectiveFee: Double get() = fees?.base ?: fee
    
    /** Get ancestor fee */
    val effectiveAncestorFees: Double get() = fees?.ancestor ?: ancestorfees
    
    /** Get descendant fee */  
    val effectiveDescendantFees: Double get() = fees?.descendant ?: descendantfees
}

@Serializable
data class MempoolFees(
    val base: Double = 0.0,
    val modified: Double = 0.0,
    val ancestor: Double = 0.0,
    val descendant: Double = 0.0
)
