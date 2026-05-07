package com.cortex.pos.data.models

data class LoginResponse(
    val access_token: String,
    val token_type: String,
    val role: String,
    val full_name: String,
    val tenant_id: Int?
)

data class Room(
    val id: Int,
    val name: String,
    val capacity: Int,
    val status: String,
    val room_type: String
)

data class Category(
    val id: Int,
    val name: String,
    val sort_order: Int = 0,
    val is_active: Boolean = true
)

data class Product(
    val id: Int,
    val name: String,
    val price: Double,
    val unit: String,
    val category_id: Int,
    val is_active: Boolean = true
)

data class OrderItemDetail(
    val id: Int,
    val product_id: Int?,
    val product_name: String,
    val quantity: Double,
    val unit_price: Double,
    val total_price: Double
)

data class Order(
    val id: Int,
    val order_type: String,
    val status: String,
    val customer_phone: String?,
    val customer_name: String?,
    val note: String?,
    val total: Double,
    val subtotal: Double,
    val discount: Double = 0.0,
    val created_at: String,
    val room_id: Int?,
    val items: List<OrderItemDetail> = emptyList()
)

data class PaymentResult(
    val message: String,
    val order_id: Int,
    val total: Double,
    val discount: Double,
    val method: String,
    val items: List<ReceiptItem>,
    val paid_at: String
)

data class ReceiptItem(
    val product_name: String,
    val quantity: Double,
    val unit_price: Double,
    val total_price: Double
)

data class Statistics(
    val period: String,
    val total_revenue: Double,
    val total_orders: Int,
    val dine_in_orders: Int,
    val takeaway_orders: Int,
    val bot_orders: Int,
    val avg_order: Double,
    val cash: Double = 0.0,
    val card: Double = 0.0,
    val online: Double = 0.0
)

data class InventoryItem(
    val id: Int,
    val product_id: Int,
    val product_name: String,
    val quantity: Double,
    val min_quantity: Double,
    val unit_cost: Double,
    val supplier: String = "",
    val status: String
)

data class TenantStats(
    val id: Int,
    val name: String,
    val type: String?,
    val phone: String?,
    val is_active: Boolean,
    val created_at: String,
    val total_revenue: Double,
    val total_orders: Int,
    val users_count: Int
)

data class SuperStats(
    val total_tenants: Int,
    val active_tenants: Int,
    val tenants: List<TenantStats>
)

data class Worker(
    val id: Int,
    val full_name: String,
    val login: String,
    val role: String,
    val is_active: Boolean
)

data class BotConfig(
    val configured: Boolean,
    val bot_token: String = "",
    val welcome_message: String = "",
    val is_active: Boolean = false,
    val admin_chat_id: String = ""
)
