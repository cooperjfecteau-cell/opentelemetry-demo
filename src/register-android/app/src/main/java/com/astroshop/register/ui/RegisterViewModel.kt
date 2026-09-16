// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astroshop.register.config.RegisterConfig
import com.astroshop.register.data.ApiResult
import com.astroshop.register.data.Product
import com.astroshop.register.data.RegisterRepository
import com.astroshop.register.data.priceUsdAmount
import com.astroshop.register.rum.RegisterRum
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import kotlin.math.max
import kotlin.math.roundToLong

enum class Screen(val viewName: String) {
    SIGN_IN(RegisterRum.VIEW_SIGN_IN),
    SALE(RegisterRum.VIEW_SALE),
    TENDER(RegisterRum.VIEW_TENDER),
    RECEIPT(RegisterRum.VIEW_RECEIPT),
}

enum class TenderType { CARD, CASH }

data class LineItem(val product: Product, val quantity: Int)

/** What the last lookup did, which is what the sale screen renders above the tap grid. */
sealed interface LookupState {
    data object Idle : LookupState
    data class Loading(val productId: String) : LookupState
    data class Added(val name: String) : LookupState
    data class NotFound(val productId: String) : LookupState
    data class Failed(val productId: String, val status: Int) : LookupState
    data class CartError(val productId: String, val name: String, val status: Int) : LookupState
}

data class Receipt(
    val orderId: String,
    val items: List<LineItem>,
    val total: Double,
    val tenderType: TenderType,
    val tendered: Double,
    val change: Double,
    val cashier: String,
    val completedAtMillis: Long,
)

data class RegisterUiState(
    val screen: Screen = Screen.SIGN_IN,
    val cashier: String? = null,
    val basket: List<LineItem> = emptyList(),
    val transactionOpen: Boolean = false,
    /** Consecutive lookups the catalog failed to answer. Three or more blocks the sale screen. */
    val lookupFailures: Int = 0,
    val lookup: LookupState = LookupState.Idle,
    val lastFailedProductId: String = "",
    val lastReceipt: Receipt? = null,
    val charging: Boolean = false,
    /** Non-null once checkout has refused the order; the value is the HTTP status, 0 for transport. */
    val chargeFailedStatus: Int? = null,
) {
    val itemCount: Int get() = basket.sumOf { it.quantity }
    val total: Double
        get() = (basket.sumOf { it.product.priceUsdAmount() * it.quantity } * 100).roundToLong() / 100.0
    val catalogDown: Boolean get() = lookupFailures >= LOOKUP_FAILURES_BEFORE_OVERLAY
}

const val LOOKUP_FAILURES_BEFORE_OVERLAY = 3

/**
 * Register state and the actions that drive both Astro Shop and the session telemetry: sign-in and
 * sign-out, scans, voids and tender, plus the 15-minute idle lock.
 *
 * Every Dynatrace call goes through [RegisterRum]; nothing here talks to the agent directly.
 */
class RegisterViewModel(
    private val repository: RegisterRepository = RegisterRepository(),
) : ViewModel() {

    private val _state = MutableStateFlow(RegisterUiState())
    val state: StateFlow<RegisterUiState> = _state.asStateFlow()

    /** Failed lookups in the open transaction, for `transaction_void`'s `failed_lookup_count`. */
    private var failedLookupsInTransaction = 0

    @Volatile
    private var lastActivityMillis = System.currentTimeMillis()

    init {
        RegisterRum.startView(Screen.SIGN_IN.viewName)
        viewModelScope.launch {
            while (true) {
                delay(IDLE_CHECK_INTERVAL_MS)
                if (_state.value.cashier != null &&
                    System.currentTimeMillis() - lastActivityMillis >= IDLE_TIMEOUT_MS
                ) {
                    lockIdle()
                }
            }
        }
    }

    /** Any touch anywhere on the register counts as cashier activity for the idle lock. */
    fun markActivity() {
        lastActivityMillis = System.currentTimeMillis()
    }

    fun signIn(cashierNumber: String) {
        // The cashier id is pseudonymous; the number is all the register ever keeps, and the PIN
        // never leaves the sign-in screen.
        val cashierId = "cashier-${cashierNumber.trim()}"
        // A fresh cart key per shift keeps one cashier's basket out of the next one's.
        repository.cartSessionId = UUID.randomUUID().toString().replace("-", "")
        RegisterRum.signIn(cashierId)
        failedLookupsInTransaction = 0
        markActivity()
        _state.value = RegisterUiState(screen = Screen.SALE, cashier = cashierId)
        RegisterRum.startView(Screen.SALE.viewName)
    }

    fun signOut() {
        viewModelScope.launch {
            // Signing out never leaves a transaction dangling in the session.
            voidTransactionInternal("cashier")
            RegisterRum.endShiftSession()
            _state.value = RegisterUiState()
            RegisterRum.startView(Screen.SIGN_IN.viewName)
        }
    }

    /** The 15-minute lock. Same session boundary as a sign-out, then back to the sign-in screen. */
    private suspend fun lockIdle() {
        voidTransactionInternal("cashier")
        RegisterRum.endShiftSession()
        _state.value = RegisterUiState()
        RegisterRum.startView(Screen.SIGN_IN.viewName)
    }

    fun scan(rawProductId: String) {
        val productId = rawProductId.trim().uppercase(Locale.US)
        if (productId.isEmpty() || _state.value.lookup is LookupState.Loading) return
        markActivity()

        viewModelScope.launch {
            _state.update { it.copy(lookup = LookupState.Loading(productId), transactionOpen = true) }

            when (val lookup = repository.lookupProduct(productId)) {
                is ApiResult.Failed -> {
                    // Astro Shop answers 500 for an unknown id and for a catalog outage alike, so
                    // `result` is the only thing that separates a mistype from the fault.
                    val result = if (lookup.status == 404) "not_found" else "error"
                    RegisterRum.lookupFailed(productId, result, lookup.status)
                    failedLookupsInTransaction++
                    if (result == "not_found") {
                        // The catalog answered, so it is reachable: the failure run resets.
                        _state.update { it.copy(lookup = LookupState.NotFound(productId), lookupFailures = 0) }
                    } else {
                        _state.update {
                            it.copy(
                                lookup = LookupState.Failed(productId, lookup.status),
                                lookupFailures = it.lookupFailures + 1,
                                lastFailedProductId = productId,
                            )
                        }
                    }
                }

                is ApiResult.Ok -> {
                    val product = lookup.value?.takeIf { !it.id.isNullOrBlank() }
                    if (product == null) {
                        RegisterRum.lookupFailed(productId, "not_found", 200)
                        failedLookupsInTransaction++
                        _state.update { it.copy(lookup = LookupState.NotFound(productId), lookupFailures = 0) }
                        return@launch
                    }

                    when (val cart = repository.addToCart(product.id!!, 1)) {
                        is ApiResult.Failed -> _state.update {
                            it.copy(
                                lookup = LookupState.CartError(productId, product.name.orEmpty(), cart.status),
                                lookupFailures = 0,
                            )
                        }

                        is ApiResult.Ok -> _state.update {
                            it.copy(
                                basket = addLine(it.basket, product, 1),
                                lookup = LookupState.Added(product.name.orEmpty()),
                                lookupFailures = 0,
                            )
                        }
                    }
                }
            }
        }
    }

    fun changeQuantity(productId: String, delta: Int) {
        markActivity()
        val line = _state.value.basket.find { it.product.id == productId } ?: return

        viewModelScope.launch {
            if (delta > 0) {
                if (repository.addToCart(productId, delta) is ApiResult.Ok) {
                    _state.update { it.copy(basket = addLine(it.basket, line.product, delta)) }
                }
                return@launch
            }

            // The cart service can only add or empty, so taking an item off rebuilds the cart.
            val items = _state.value.basket
                .map { if (it.product.id == productId) it.copy(quantity = it.quantity + delta) else it }
                .filter { it.quantity > 0 }
            _state.update { it.copy(basket = items) }
            if (repository.emptyCart() !is ApiResult.Ok) return@launch
            items.forEach { repository.addToCart(it.product.id!!, it.quantity) }
        }
    }

    fun voidTransaction(reason: String) {
        markActivity()
        viewModelScope.launch {
            voidTransactionInternal(reason)
            _state.update { it.copy(screen = Screen.SALE, lookup = LookupState.Idle) }
            RegisterRum.startView(Screen.SALE.viewName)
        }
    }

    private suspend fun voidTransactionInternal(reason: String) {
        val snapshot = _state.value
        if (!snapshot.transactionOpen) return
        if (snapshot.basket.isNotEmpty()) repository.emptyCart()
        RegisterRum.transactionVoid(reason, snapshot.itemCount, failedLookupsInTransaction)
        failedLookupsInTransaction = 0
        _state.update {
            it.copy(basket = emptyList(), transactionOpen = false, lookupFailures = 0, chargeFailedStatus = null)
        }
    }

    fun goToTender() {
        markActivity()
        _state.update { it.copy(screen = Screen.TENDER, chargeFailedStatus = null) }
        RegisterRum.startView(Screen.TENDER.viewName)
    }

    fun backToSale() {
        markActivity()
        _state.update { it.copy(screen = Screen.SALE) }
        RegisterRum.startView(Screen.SALE.viewName)
    }

    fun newSale() {
        markActivity()
        _state.update { it.copy(screen = Screen.SALE, lastReceipt = null, lookup = LookupState.Idle) }
        RegisterRum.startView(Screen.SALE.viewName)
    }

    fun tender(type: TenderType, tendered: Double) {
        markActivity()
        viewModelScope.launch {
            val snapshot = _state.value
            val total = snapshot.total
            val itemCount = snapshot.itemCount
            _state.update { it.copy(charging = true, chargeFailedStatus = null) }

            val orderId = when (val order = repository.checkout()) {
                is ApiResult.Failed -> {
                    _state.update { it.copy(charging = false, chargeFailedStatus = order.status) }
                    return@launch
                }

                is ApiResult.Ok -> order.value?.orderId
            }

            if (orderId.isNullOrBlank()) {
                _state.update { it.copy(charging = false, chargeFailedStatus = 0) }
                return@launch
            }

            RegisterRum.transactionComplete(total, itemCount, orderId)
            failedLookupsInTransaction = 0
            _state.update {
                it.copy(
                    screen = Screen.RECEIPT,
                    charging = false,
                    basket = emptyList(),
                    transactionOpen = false,
                    lookupFailures = 0,
                    lookup = LookupState.Idle,
                    lastReceipt = Receipt(
                        orderId = orderId,
                        items = snapshot.basket,
                        total = total,
                        tenderType = type,
                        tendered = tendered,
                        change = max(0.0, tendered - total),
                        cashier = snapshot.cashier.orEmpty(),
                        completedAtMillis = System.currentTimeMillis(),
                    ),
                )
            }
            RegisterRum.startView(Screen.RECEIPT.viewName)
        }
    }

    fun dismissLookupNotice() {
        _state.update { it.copy(lookup = LookupState.Idle) }
    }

    fun storeLabel(): String = with(RegisterConfig.get()) {
        "Store $storeId · $storeName · Register $registerId"
    }

    private fun addLine(items: List<LineItem>, product: Product, quantity: Int): List<LineItem> {
        val existing = items.find { it.product.id == product.id }
        return if (existing == null) {
            items + LineItem(product, quantity)
        } else {
            items.map { if (it.product.id == product.id) it.copy(quantity = it.quantity + quantity) else it }
        }
    }

    private companion object {
        const val IDLE_TIMEOUT_MS = 15L * 60 * 1000
        // The check only has to land within a sweep of the 15-minute mark.
        const val IDLE_CHECK_INTERVAL_MS = 15_000L
    }
}
