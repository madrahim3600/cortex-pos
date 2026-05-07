package com.cortex.pos.data.api

import com.cortex.pos.data.models.*
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

object ApiService {
    private const val BASE_URL = "https://cortex-pos-cortex.up.railway.app"
    private val gson = Gson()

    // ⚡ APK tezlashtirish: logging OFF, connection pool optimallashtirildi
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private fun encode(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun emptyPost() = FormBody.Builder().build()
    private fun jsonBody(json: String) =
        json.toRequestBody("application/json".toMediaType())

    // ── AUTH ──

    suspend fun login(username: String, password: String): Result<LoginResponse> =
        withContext(Dispatchers.IO) {
            try {
                val body = FormBody.Builder()
                    .add("username", username)
                    .add("password", password)
                    .build()
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/auth/login").post(body).build()
                ).execute()
                val resBody = res.body?.string() ?: ""
                if (res.isSuccessful)
                    Result.success(gson.fromJson(resBody, LoginResponse::class.java))
                else
                    Result.failure(Exception("Login xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    /** Token to'g'ri yoki yo'qligini tekshirish (saqlangan token uchun) */
    suspend fun validateToken(token: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/auth/me")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                res.isSuccessful
            } catch (e: Exception) {
                // Internet yo'q bo'lsa ham token saqlangan deb hisoblaymiz
                true
            }
        }

    suspend fun changePassword(token: String, oldPassword: String, newPassword: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/auth/change-password?" +
                        "old_password=${encode(oldPassword)}&new_password=${encode(newPassword)}"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (res.isSuccessful) Result.success(true)
                else Result.failure(Exception(res.body?.string() ?: "Xato"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── ROOMS ──

    suspend fun getRooms(token: String): Result<List<Room>> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/rooms")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "[]"
                if (res.isSuccessful) {
                    val type = object : TypeToken<List<Room>>() {}.type
                    Result.success(gson.fromJson(body, type))
                } else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createRoom(token: String, name: String, capacity: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/rooms?name=${encode(name)}&capacity=$capacity&room_type=standard"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateRoom(token: String, roomId: Int, name: String, capacity: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/rooms/$roomId?name=${encode(name)}&capacity=$capacity"
                val res = client.newCall(
                    Request.Builder().url(url).patch(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteRoom(token: String, roomId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/rooms/$roomId").delete()
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── CATEGORIES ──

    suspend fun getCategories(token: String): Result<List<Category>> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/categories")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "[]"
                if (res.isSuccessful) {
                    val type = object : TypeToken<List<Category>>() {}.type
                    Result.success(gson.fromJson(body, type))
                } else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createCategory(token: String, name: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/categories?name=${encode(name)}")
                        .post(emptyPost()).addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteCategory(token: String, categoryId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/categories/$categoryId").delete()
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── PRODUCTS ──

    suspend fun getProducts(token: String): Result<List<Product>> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/products")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "[]"
                if (res.isSuccessful) {
                    val type = object : TypeToken<List<Product>>() {}.type
                    Result.success(gson.fromJson(body, type))
                } else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createProduct(token: String, name: String, price: Double, unit: String, categoryId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/products?name=${encode(name)}&price=$price&unit=${encode(unit)}&category_id=$categoryId"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (res.isSuccessful) Result.success(true)
                else Result.failure(Exception(res.body?.string() ?: "Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateProduct(token: String, productId: Int, name: String, price: Double, unit: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/products/$productId?name=${encode(name)}&price=$price&unit=${encode(unit)}"
                val res = client.newCall(
                    Request.Builder().url(url).patch(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteProduct(token: String, productId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/products/$productId").delete()
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── ORDERS ──

    suspend fun getOrders(token: String, status: String? = null, orderType: String? = null): Result<List<Order>> =
        withContext(Dispatchers.IO) {
            try {
                var url = "$BASE_URL/orders"
                val params = mutableListOf<String>()
                if (status != null) params.add("status=$status")
                if (orderType != null) params.add("order_type=$orderType")
                if (params.isNotEmpty()) url += "?" + params.joinToString("&")
                val res = client.newCall(
                    Request.Builder().url(url)
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "[]"
                if (res.isSuccessful) {
                    val type = object : TypeToken<List<Order>>() {}.type
                    Result.success(gson.fromJson(body, type))
                } else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun getOrder(token: String, orderId: Int): Result<Order> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/orders/$orderId")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "{}"
                if (res.isSuccessful)
                    Result.success(gson.fromJson(body, Order::class.java))
                else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createOrder(
        token: String, orderType: String, roomId: Int? = null,
        customerName: String = "", customerPhone: String = "", note: String = ""
    ): Result<Int> =
        withContext(Dispatchers.IO) {
            try {
                var url = "$BASE_URL/orders?order_type=$orderType" +
                        "&customer_name=${encode(customerName)}" +
                        "&customer_phone=${encode(customerPhone)}" +
                        "&note=${encode(note)}"
                if (roomId != null) url += "&room_id=$roomId"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "{}"
                if (res.isSuccessful) {
                    val obj = JsonParser.parseString(body).asJsonObject
                    Result.success(obj.get("id").asInt)
                } else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun addOrderItem(token: String, orderId: Int, productId: Int, quantity: Float): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/orders/$orderId/items?product_id=$productId&quantity=$quantity"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateOrderStatus(token: String, orderId: Int, status: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/orders/$orderId/status?new_status=$status"
                val res = client.newCall(
                    Request.Builder().url(url).patch(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun payOrder(token: String, orderId: Int, method: String, amount: Double, discount: Double = 0.0): Result<PaymentResult> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/orders/$orderId/pay?method=$method&amount=$amount&discount=$discount"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "{}"
                if (res.isSuccessful)
                    Result.success(gson.fromJson(body, PaymentResult::class.java))
                else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── ADMIN → MIJOZGA XABAR ──

    suspend fun sendMessageToCustomer(token: String, orderId: Int, message: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/orders/$orderId/send-message?message=${encode(message)}"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (res.isSuccessful) Result.success(true)
                else {
                    val body = res.body?.string() ?: ""
                    Result.failure(Exception(body))
                }
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── STATISTICS ──

    suspend fun getStatistics(token: String, period: String = "today"): Result<Statistics> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/statistics?period=$period")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "{}"
                if (res.isSuccessful)
                    Result.success(gson.fromJson(body, Statistics::class.java))
                else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── INVENTORY ──

    suspend fun getInventory(token: String): Result<List<InventoryItem>> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/inventory")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "[]"
                if (res.isSuccessful) {
                    val type = object : TypeToken<List<InventoryItem>>() {}.type
                    Result.success(gson.fromJson(body, type))
                } else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun addInventory(token: String, productId: Int, quantity: Double, unitCost: Double = 0.0, supplier: String = ""): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/inventory/$productId/add?quantity=$quantity&unit_cost=$unitCost&supplier=${encode(supplier)}"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (res.isSuccessful) Result.success(true)
                else Result.failure(Exception(res.body?.string() ?: "Xato"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateInventory(token: String, inventoryId: Int, quantity: Double, minQuantity: Double): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/inventory/$inventoryId?quantity=$quantity&min_quantity=$minQuantity"
                val res = client.newCall(
                    Request.Builder().url(url).patch(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── SUPER ADMIN ──

    suspend fun getSuperStats(token: String): Result<SuperStats> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/superadmin/stats")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "{}"
                if (res.isSuccessful)
                    Result.success(gson.fromJson(body, SuperStats::class.java))
                else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createTenant(token: String, name: String, type: String, address: String, phone: String, adminName: String, adminLogin: String, adminPassword: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/superadmin/tenants?name=${encode(name)}&type=${encode(type)}" +
                        "&address=${encode(address)}&phone=${encode(phone)}" +
                        "&admin_name=${encode(adminName)}&admin_login=${encode(adminLogin)}" +
                        "&admin_password=${encode(adminPassword)}"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (res.isSuccessful) Result.success(true)
                else Result.failure(Exception(res.body?.string() ?: "Xato"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateTenant(token: String, tenantId: Int, name: String, type: String, phone: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/superadmin/tenants/$tenantId?name=${encode(name)}&type=${encode(type)}&phone=${encode(phone)}"
                val res = client.newCall(
                    Request.Builder().url(url).put(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteTenant(token: String, tenantId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/superadmin/tenants/$tenantId").delete()
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun toggleTenant(token: String, tenantId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/superadmin/tenants/$tenantId/toggle")
                        .patch(emptyPost()).addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── WORKERS ──

    suspend fun getWorkers(token: String): Result<List<Worker>> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/workers")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "[]"
                if (res.isSuccessful) {
                    val type = object : TypeToken<List<Worker>>() {}.type
                    Result.success(gson.fromJson(body, type))
                } else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun createWorker(token: String, fullName: String, login: String, password: String, role: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/workers?full_name=${encode(fullName)}&login=${encode(login)}&password=${encode(password)}&role=$role"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (res.isSuccessful) Result.success(true)
                else Result.failure(Exception(res.body?.string() ?: "Xato"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun updateWorker(token: String, workerId: Int, fullName: String, role: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/workers/$workerId?full_name=${encode(fullName)}&role=$role"
                val res = client.newCall(
                    Request.Builder().url(url).patch(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteWorker(token: String, workerId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/workers/$workerId").delete()
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    // ── BOT CONFIG ──

    suspend fun getBotConfig(token: String): Result<BotConfig> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/bot-config")
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                val body = res.body?.string() ?: "{}"
                if (res.isSuccessful)
                    Result.success(gson.fromJson(body, BotConfig::class.java))
                else Result.failure(Exception("Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun saveBotToken(
        token: String, botToken: String,
        welcomeMessage: String = "Xush kelibsiz!",
        adminChatId: String = ""
    ): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/bot-config?" +
                        "bot_token=${encode(botToken)}" +
                        "&welcome_message=${encode(welcomeMessage)}" +
                        "&admin_chat_id=${encode(adminChatId)}"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun deleteBotConfig(token: String): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/bot-config").delete()
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                Result.success(res.isSuccessful)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun removeOrderItem(token: String, orderId: Int, itemId: Int): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val res = client.newCall(
                    Request.Builder().url("$BASE_URL/orders/$orderId/items/$itemId").delete()
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (res.isSuccessful) Result.success(true)
                else Result.failure(Exception(res.body?.string() ?: "Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }

    suspend fun addExtraCharge(token: String, orderId: Int, description: String, amount: Double): Result<Boolean> =
        withContext(Dispatchers.IO) {
            try {
                val url = "$BASE_URL/orders/$orderId/extra?description=${encode(description)}&amount=$amount"
                val res = client.newCall(
                    Request.Builder().url(url).post(emptyPost())
                        .addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (res.isSuccessful) Result.success(true)
                else Result.failure(Exception(res.body?.string() ?: "Xato: ${res.code}"))
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
}
