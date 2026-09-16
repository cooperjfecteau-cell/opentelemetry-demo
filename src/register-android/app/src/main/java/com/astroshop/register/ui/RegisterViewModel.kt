// Copyright The OpenTelemetry Authors
// SPDX-License-Identifier: Apache-2.0
package com.astroshop.register.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.astroshop.register.config.RegisterConfig
import com.astroshop.register.data.ApiResult
import com.astroshop.register.data.AssistantAnswer
import com.astroshop.register.data.PickupOrder
import com.astroshop.register.data.Product
import com.astroshop.register.data.RegisterRepository
import com.astroshop.register.data.countedItems
import com.astroshop.register.data.priceUsdAmount
import com.astroshop.register.rum.RegisterFlow
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

    /** The two counter tasks that are not a sale. Both are opened from, and return to, [SALE]. */
    PICKUP(RegisterRum.VIEW_PICKUP),
    ASSISTANT(RegisterRum.VIEW_ASSISTANT),
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

/** Where the cashier is in a pickup: the queue, one order, or the handover that finished it. */
sealed interface PickupState {
    data object Idle : PickupState
    data object LoadingOrders : PickupState
    data class Orders(val orders: List<PickupOrder>) : PickupState
    data class LoadingOrder(val orderId: String) : PickupState
    /** `error` is a collect the shop refused: the order stays on screen so it can be retried. */
    data class Order(val order: PickupOrder, val collecting: Boolean = false, val error: String? = null) :
        PickupState

    data class Collected(val order: PickupOrder, val collectedAt: String?) : PickupState
    data class Failed(val message: String, val status: Int) : PickupState
}

/**
 * One question and one answer. The question lives here rather than in the composable so that the
 * answer, the spinner and the text the cashier typed cannot disagree.
 */
data class AssistantState(
    val question: String = "",
    /** The item the question is about, which is what `assistant.asked` reports. May be empty. */
    val productId: String = "",
    val asking: Boolean = false,
    val answer: String? = null,
    val error: String? = null,
)

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
    /** The last item the catalog answered for, which is what the assistant is asked about. */
    val lastScanned: Product? = null,
    val pickup: PickupState = PickupState.Idle,
    val assistant: AssistantState = AssistantState(),
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

    /**
     * The correlation key of the sale currently on the screen, and the case id of the register
     * Business Flow (bluebox-demo#49). A sale gets one at the moment the register opens it - at
     * sign-in for a shift's first sale, then on every "New sale" or void - so that every case in
     * the flow starts at step one and conversion means "sales that took money".
     */
    private var transactionId: String = newTransactionId()

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
        openSale(cashierId, RegisterFlow.OPENED_BY_SIGN_IN)
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

                    // Remembered for the assistant screen: a cashier asks about the item in
                    // their hand, and this is the last one the catalog could name.
                    _state.update { it.copy(lastScanned = product) }

                    when (val cart = repository.addToCart(product.id!!, 1)) {
                        is ApiResult.Failed -> _state.update {
                            it.copy(
                                lookup = LookupState.CartError(productId, product.name.orEmpty(), cart.status),
                                lookupFailures = 0,
                            )
                        }

                        is ApiResult.Ok -> {
                            _state.update {
                                it.copy(
                                    basket = addLine(it.basket, product, 1),
                                    lookup = LookupState.Added(product.name.orEmpty()),
                                    lookupFailures = 0,
                                )
                            }
                            // Step two of the flow, and the only one that repeats.
                            RegisterFlow.itemAdded(
                                transactionId,
                                _state.value.cashier.orEmpty(),
                                product.id!!,
                                product.name.orEmpty(),
                                product.priceUsdAmount(),
                                _state.value.itemCount,
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
            // A void ends that case. The register is still open for business, so the next sale
            // starts here rather than waiting for an item to be scanned into a closed case.
            _state.value.cashier?.let { openSale(it, RegisterFlow.OPENED_BY_NEW_SALE) }
        }
    }

    private suspend fun voidTransactionInternal(reason: String) {
        val snapshot = _state.value
        if (!snapshot.transactionOpen) return
        if (snapshot.basket.isNotEmpty()) repository.emptyCart()
        RegisterRum.transactionVoid(transactionId, reason, snapshot.itemCount, failedLookupsInTransaction)
        RegisterFlow.saleVoided(
            transactionId,
            snapshot.cashier.orEmpty(),
            reason,
            snapshot.itemCount,
            failedLookupsInTransaction,
        )
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
        _state.value.cashier?.let { openSale(it, RegisterFlow.OPENED_BY_NEW_SALE) }
    }

    /**
     * Opens the next sale: a fresh correlation key, step one of the flow, and the shift identity
     * re-applied.
     *
     * The identity is re-sent here rather than only at sign-in because the agent's idle timeout is
     * shorter than the register's 15-minute lock, so a quiet shift can split into a session that
     * never saw the sign-in (see RegisterRum.applySessionIdentity).
     */
    private fun openSale(cashierId: String, openedBy: String) {
        transactionId = newTransactionId()
        RegisterRum.applySessionIdentity(cashierId)
        RegisterFlow.saleOpened(transactionId, cashierId, openedBy)
    }

    fun tender(type: TenderType, tendered: Double) {
        markActivity()
        viewModelScope.launch {
            val snapshot = _state.value
            val total = snapshot.total
            val itemCount = snapshot.itemCount
            _state.update { it.copy(charging = true, chargeFailedStatus = null) }

            // Step three, before checkout runs, so that a checkout Astro Shop refuses shows up as a
            // drop-off between tender and completion rather than never reaching the flow at all.
            val tenderName = type.name.lowercase(Locale.US)
            RegisterFlow.tenderStarted(
                transactionId,
                snapshot.cashier.orEmpty(),
                tenderName,
                total,
                itemCount,
            )

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

            RegisterRum.transactionComplete(transactionId, total, itemCount, orderId)
            // Step four: the converting step, and the one that carries the money.
            RegisterFlow.saleCompleted(
                transactionId,
                snapshot.cashier.orEmpty(),
                total,
                itemCount,
                tenderName,
                orderId,
            )
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

    // ---- Order pickup (bluebox-demo#51) -------------------------------------------------------

    /**
     * Opens the pickup queue for this store. The open sale is left exactly as it is underneath: a
     * cashier half way through ringing items up can still hand over an online order.
     */
    fun openPickup() {
        markActivity()
        _state.update { it.copy(screen = Screen.PICKUP) }
        RegisterRum.startView(Screen.PICKUP.viewName)
        loadReadyOrders()
    }

    /** Also the way back from an order, so a collected one drops off the queue on return. */
    fun loadReadyOrders() {
        markActivity()
        viewModelScope.launch {
            _state.update { it.copy(pickup = PickupState.LoadingOrders) }
            val storeId = RegisterConfig.get().storeId
            when (val result = repository.pickup.readyOrders(storeId)) {
                is ApiResult.Failed -> _state.update {
                    it.copy(
                        pickup = PickupState.Failed(
                            "Orders waiting at this store could not be loaded",
                            result.status,
                        ),
                    )
                }

                is ApiResult.Ok -> _state.update { it.copy(pickup = PickupState.Orders(result.value)) }
            }
        }
    }

    fun selectPickupOrder(orderId: String) {
        markActivity()
        viewModelScope.launch {
            _state.update { it.copy(pickup = PickupState.LoadingOrder(orderId)) }
            when (val result = repository.pickup.order(orderId)) {
                is ApiResult.Failed -> _state.update {
                    it.copy(pickup = PickupState.Failed("Order could not be opened", result.status))
                }

                is ApiResult.Ok -> {
                    val order = result.value
                    _state.update { it.copy(pickup = PickupState.Order(order)) }
                    // The pickup starts when its contents are in front of the cashier: before that
                    // there is a queue, not an order, and nothing to name the event after. An order
                    // the shop does not recognise never started.
                    if (order.status != STATUS_UNKNOWN) {
                        RegisterFlow.pickupStarted(
                            _state.value.cashier.orEmpty(),
                            order.orderId.orEmpty(),
                            order.countedItems(),
                            order.total ?: 0.0,
                        )
                    }
                }
            }
        }
    }

    /** The handover. The shop is told before the cashier is, so a refusal keeps the bag behind. */
    fun collectPickupOrder() {
        val current = _state.value.pickup as? PickupState.Order ?: return
        val orderId = current.order.orderId ?: return
        markActivity()
        viewModelScope.launch {
            _state.update { it.copy(pickup = current.copy(collecting = true, error = null)) }
            val cashier = _state.value.cashier.orEmpty()
            when (val result = repository.pickup.collect(orderId, RegisterConfig.get().registerId, cashier)) {
                is ApiResult.Failed -> _state.update {
                    it.copy(
                        pickup = current.copy(
                            collecting = false,
                            error = "The shop did not accept the collection (HTTP " +
                                statusLabel(result.status) + ").",
                        ),
                    )
                }

                is ApiResult.Ok -> {
                    RegisterFlow.pickupCollected(
                        cashier,
                        orderId,
                        current.order.countedItems(),
                        current.order.total ?: 0.0,
                    )
                    _state.update {
                        it.copy(pickup = PickupState.Collected(current.order, result.value.collectedAt))
                    }
                }
            }
        }
    }

    // ---- The shop assistant ---------------------------------------------------------------------

    /**
     * Opens the assistant with the question already written, when there is an item to write it
     * about. A cashier with a customer waiting should be one tap from an answer.
     */
    fun openAssistant() {
        markActivity()
        val product = _state.value.lastScanned
        val name = product?.name.orEmpty()
        _state.update {
            it.copy(
                screen = Screen.ASSISTANT,
                assistant = AssistantState(
                    question = if (name.isBlank()) {
                        ""
                    } else {
                        "A customer is asking about the " + name + ". What should I tell them?"
                    },
                    productId = product?.id.orEmpty(),
                ),
            )
        }
        RegisterRum.startView(Screen.ASSISTANT.viewName)
    }

    fun setAssistantQuestion(question: String) {
        _state.update { it.copy(assistant = it.assistant.copy(question = question)) }
    }

    /** One question, one answer, one business event - whichever way it went. */
    fun askAssistant() {
        val question = _state.value.assistant.question.trim()
        if (question.isEmpty() || _state.value.assistant.asking) return
        markActivity()
        viewModelScope.launch {
            _state.update {
                it.copy(assistant = it.assistant.copy(asking = true, answer = null, error = null))
            }
            // Measured here, so it is what the cashier waited: the whole round trip through
            // frontend-proxy and the chatbot to the assistant, not the assistant's own span.
            val startedAt = System.nanoTime()
            val answer = repository.assistant.ask(question)
            val elapsedMs = (System.nanoTime() - startedAt) / 1_000_000
            val cashier = _state.value.cashier.orEmpty()
            val productId = _state.value.assistant.productId

            when (answer) {
                is AssistantAnswer.Ok -> {
                    RegisterFlow.assistantAsked(cashier, productId, elapsedMs, RegisterFlow.ASSISTANT_OK)
                    _state.update {
                        it.copy(assistant = it.assistant.copy(asking = false, answer = answer.text))
                    }
                }

                is AssistantAnswer.Failed -> {
                    RegisterFlow.assistantAsked(cashier, productId, elapsedMs, RegisterFlow.ASSISTANT_ERROR)
                    _state.update {
                        it.copy(assistant = it.assistant.copy(asking = false, error = answer.detail))
                    }
                }
            }
        }
    }

    /** Both side screens come back to the sale, which was never disturbed. */
    fun backToSaleScreen() {
        markActivity()
        _state.update { it.copy(screen = Screen.SALE, pickup = PickupState.Idle) }
        RegisterRum.startView(Screen.SALE.viewName)
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

    /** 0 means the call never reached a server, which reads better as "error" at the counter. */
    private fun statusLabel(status: Int): String = if (status == 0) "error" else status.toString()

    private companion object {
        /** Opaque, client-side, and unique per sale: the Business Flow's case id. */
        fun newTransactionId(): String = UUID.randomUUID().toString().replace("-", "")

        /** The order-pickup API's word for an order it cannot find. */
        const val STATUS_UNKNOWN = "unknown"

        const val IDLE_TIMEOUT_MS = 15L * 60 * 1000
        // The check only has to land within a sweep of the 15-minute mark.
        const val IDLE_CHECK_INTERVAL_MS = 15_000L
    }
}
