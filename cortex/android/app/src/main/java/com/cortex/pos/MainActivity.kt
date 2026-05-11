@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package com.cortex.pos

import android.os.Bundle
import android.content.Intent
import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.platform.LocalContext
import com.cortex.pos.data.api.ApiService
import com.cortex.pos.data.models.*
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.graphics.pdf.PdfDocument
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Environment
import java.io.File
import java.io.FileOutputStream

// ── RANGLAR ──
val DarkBlue = Color(0xFF1a2744)
val MidBlue = Color(0xFF2563eb)
val Accent = Color(0xFFf59e0b)
val GreenOk = Color(0xFF16a34a)
val RedBusy = Color(0xFFdc2626)
val BgGray = Color(0xFFf8fafc)
val TextGray = Color(0xFF475569)
val OrangeWarn = Color(0xFFea580c)
val Purple = Color(0xFF8b5cf6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                CortexPOSApp()
            }
        }
    }
}

@Composable
fun CortexPOSApp() {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("cortex_prefs", Context.MODE_PRIVATE)
    var token by remember { mutableStateOf(prefs.getString("token", "") ?: "") }
    var userRole by remember { mutableStateOf(prefs.getString("role", "") ?: "") }
    var fullName by remember { mutableStateOf(prefs.getString("full_name", "") ?: "") }
    var checkingToken by remember { mutableStateOf(token.isNotEmpty()) }

    // ⚡ Token saqlangan bo'lsa - har safar offline ham ishlaydi (server bilan tekshirish)
    LaunchedEffect(Unit) {
        if (token.isNotEmpty()) {
            // Internetsiz ham app ochilishi uchun - validateToken true qaytaradi xato bo'lsa
            val isValid = ApiService.validateToken(token)
            if (!isValid) {
                // Token eskirgan - tozalaymiz
                token = ""; userRole = ""; fullName = ""
                prefs.edit().clear().apply()
            }
            checkingToken = false
        }
    }

    when {
        checkingToken -> Box(
            modifier = Modifier.fillMaxSize().background(DarkBlue),
            contentAlignment = Alignment.Center
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("CORTEX POS", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Accent)
                Spacer(Modifier.height(16.dp))
                CircularProgressIndicator(color = Accent)
            }
        }
        token.isEmpty() -> LoginScreen { t, role, name ->
            token = t; userRole = role; fullName = name
            prefs.edit()
                .putString("token", t)
                .putString("role", role)
                .putString("full_name", name)
                .apply()
        }
        else -> MainScreen(token, userRole, fullName) {
            token = ""; userRole = ""; fullName = ""
            prefs.edit().clear().apply()
        }
    }
}

// ══════════════════════════════════════════════
// LOGIN
// ══════════════════════════════════════════════
@Composable
fun LoginScreen(onSuccess: (String, String, String) -> Unit) {
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier.fillMaxSize().background(DarkBlue),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp).verticalScroll(rememberScrollState())
        ) {
            // App logo iconi
            Box(
                modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp)).background(Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.Restaurant, null, tint = Color.White, modifier = Modifier.size(48.dp))
            }
            Spacer(Modifier.height(16.dp))
            Text("CORTEX POS", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Accent)
            Text("Restoran Boshqaruv Tizimi", fontSize = 14.sp, color = Color.White.copy(alpha = 0.7f))
            Spacer(Modifier.height(40.dp))
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(24.dp)) {
                    Text("Kirish", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
                    Spacer(Modifier.height(16.dp))
                    OutlinedTextField(
                        value = login, onValueChange = { login = it },
                        label = { Text("Login") },
                        leadingIcon = { Icon(Icons.Default.Person, null, tint = MidBlue) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Parol") },
                        leadingIcon = { Icon(Icons.Default.Lock, null, tint = MidBlue) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    if (error.isNotEmpty()) {
                        Spacer(Modifier.height(8.dp))
                        Text(error, color = RedBusy, fontSize = 13.sp)
                    }
                    Spacer(Modifier.height(20.dp))
                    Button(
                        onClick = {
                            scope.launch {
                                loading = true; error = ""
                                ApiService.login(login, password)
                                    .onSuccess { onSuccess(it.access_token, it.role, it.full_name) }
                                    .onFailure { error = "Login yoki parol xato!" }
                                loading = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth().height(50.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                        enabled = !loading && login.isNotEmpty() && password.isNotEmpty()
                    ) {
                        if (loading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(24.dp))
                        else Text("Kirish", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════
// MAIN SCREEN
// ══════════════════════════════════════════════
@Composable
fun MainScreen(token: String, userRole: String, fullName: String, onLogout: () -> Unit) {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = when (userRole) {
        "super_admin" -> listOf("Bizneslar", "Statistika", "Sozlamalar")
        "admin" -> listOf("Xonalar", "Menyu", "Ombor", "Xodimlar", "Statistika", "Sozlamalar")
        "cashier" -> listOf("Buyurtmalar", "Xonalar", "Tolov", "Statistika")
        "waiter" -> listOf("Xonalar", "Buyurtmalar")
        else -> listOf("Buyurtmalar", "Xonalar", "Tolov", "Statistika")
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier.fillMaxWidth().background(DarkBlue)
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier.size(36.dp).clip(RoundedCornerShape(10.dp)).background(Accent),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Default.Restaurant, null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column {
                    Text("CORTEX POS", fontWeight = FontWeight.Bold, color = Color.White, fontSize = 16.sp)
                    Text(fullName, fontSize = 11.sp, color = Color.White.copy(alpha = 0.8f))
                }
            }
            IconButton(onClick = onLogout, modifier = Modifier.align(Alignment.CenterEnd)) {
                Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = "Chiqish", tint = Color.White)
            }
        }

        Box(modifier = Modifier.weight(1f)) {
            when (tabs.getOrNull(selectedTab)) {
                "Buyurtmalar" -> OrdersScreen(token)
                "Xonalar" -> RoomsScreen(token, userRole)
                "Tolov" -> PaymentScreen(token)
                "Statistika" -> StatisticsScreen(token)
                "Menyu" -> MenuScreen(token, userRole)
                "Ombor" -> InventoryScreen(token, userRole)
                "Sozlamalar" -> SettingsScreen(token, userRole)
                "Xodimlar" -> WorkersScreen(token)
                "Bizneslar" -> TenantsScreen(token)
                else -> OrdersScreen(token)
            }
        }

        NavigationBar(containerColor = DarkBlue) {
            tabs.forEachIndexed { i, title ->
                NavigationBarItem(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    icon = {
                        Icon(
                            when (title) {
                                "Buyurtmalar" -> Icons.AutoMirrored.Filled.List
                                "Xonalar" -> Icons.Default.Home
                                "Tolov" -> Icons.Default.Payment
                                "Statistika" -> Icons.Default.BarChart
                                "Menyu" -> Icons.Default.RestaurantMenu
                                "Ombor" -> Icons.Default.Inventory2
                                "Sozlamalar" -> Icons.Default.Settings
                                "Xodimlar" -> Icons.Default.People
                                "Bizneslar" -> Icons.Default.Business
                                else -> Icons.Default.Circle
                            },
                            contentDescription = null,
                            tint = if (selectedTab == i) Accent else Color.White
                        )
                    },
                    label = {
                        Text(
                            title, fontSize = 9.sp,
                            color = if (selectedTab == i) Accent else Color.White,
                            maxLines = 1
                        )
                    },
                    colors = NavigationBarItemDefaults.colors(indicatorColor = MidBlue)
                )
            }
        }
    }
}

// ══════════════════════════════════════════════
// BUYURTMALAR (ORDERS - kassir uchun)
// ══════════════════════════════════════════════
@Composable
fun OrdersScreen(token: String) {
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var checkoutOrder by remember { mutableStateOf<Order?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            val all = mutableListOf<Order>()
            listOf("new", "confirmed", "preparing", "ready").forEach { s ->
                ApiService.getOrders(token, s).onSuccess { all.addAll(it) }
            }
            orders = all.sortedByDescending { it.id }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    checkoutOrder?.let { order ->
        CheckoutDialog(
            order = order, token = token,
            onDismiss = { checkoutOrder = null },
            onPaid = { checkoutOrder = null; refresh() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Buyurtmalar", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
            IconButton(onClick = { refresh() }) {
                Icon(Icons.Default.Refresh, null, tint = MidBlue)
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MidBlue)
            }
            orders.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Inbox, null, tint = TextGray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Faol buyurtma yo'q", color = TextGray)
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(orders, key = { "orders_${it.id}" }) { order ->
                    OrderRow(
                        order = order, token = token,
                        onCheckout = { checkoutOrder = order },
                        onRefresh = { refresh() }
                    )
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

@Composable
fun CartProductRow(
    product: Product,
    qty: Int,
    onAdd: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        Arrangement.SpaceBetween,
        Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(product.name, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
            Text("${"%,.0f".format(product.price)} so'm", fontSize = 12.sp, color = MidBlue)
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onRemove, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Remove, null, tint = if (qty > 0) RedBusy else TextGray)
            }
            Text(
                "$qty",
                fontWeight = FontWeight.Bold,
                modifier = Modifier.width(24.dp),
                textAlign = TextAlign.Center
            )
            IconButton(onClick = onAdd, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Add, null, tint = GreenOk)
            }
        }
    }
}

// ══════════════════════════════════════════════
// XONALAR (ROOMS) - tablar bilan
// ══════════════════════════════════════════════
@Composable
fun RoomsScreen(token: String, userRole: String = "") {
    var selectedTab by remember { mutableStateOf(2) } // Default: Xonalar
    val tabs = listOf(
        Triple("🌐 Online", Icons.Default.SmartToy, "online"),
        Triple("🥡 Olib ketish", Icons.Default.ShoppingBag, "takeaway"),
        Triple("🪑 Xonalar", Icons.Default.Chair, "rooms")
    )
    val isAdmin = userRole == "admin"

    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = DarkBlue,
            contentColor = Color.White,
            indicator = { tabPositions ->
                TabRowDefaults.Indicator(
                    Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                    color = Accent, height = 3.dp
                )
            }
        ) {
            tabs.forEachIndexed { i, (title, _, _) ->
                Tab(
                    selected = selectedTab == i,
                    onClick = { selectedTab = i },
                    text = {
                        Text(
                            title, fontSize = 13.sp, fontWeight = FontWeight.Bold,
                            color = if (selectedTab == i) Accent else Color.White.copy(0.7f)
                        )
                    }
                )
            }
        }

        when (selectedTab) {
            0 -> OnlineTab(token)
            1 -> TakeawayTab(token)
            2 -> RoomsTab(token, isAdmin)
        }
    }
}

// ══════════════════════════════════════
// XONALAR TAB - SODDALASHTIRILGAN
// ══════════════════════════════════════
@Composable
fun RoomsTab(token: String, isAdmin: Boolean) {
    var rooms by remember { mutableStateOf<List<Room>>(emptyList()) }
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editRoom by remember { mutableStateOf<Room?>(null) }
    var deleteConfirmRoom by remember { mutableStateOf<Room?>(null) }
    var orderRoom by remember { mutableStateOf<Room?>(null) }
    var checkoutOrder by remember { mutableStateOf<Order?>(null) }
    var editOrder by remember { mutableStateOf<Order?>(null) }
    var roomName by remember { mutableStateOf("") }
    var roomCapacity by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            ApiService.getRooms(token).onSuccess { rooms = it }
            // Aktiv va tarix
            val all = mutableListOf<Order>()
            listOf("new", "confirmed", "preparing", "ready").forEach { s ->
                ApiService.getOrders(token, s, "dine_in").onSuccess { all.addAll(it) }
            }
            ApiService.getOrders(token, "paid", "dine_in").onSuccess { all.addAll(it) }
            ApiService.getOrders(token, "cancelled", "dine_in").onSuccess { all.addAll(it) }
            orders = all.sortedByDescending { it.id }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        ApiService.getProducts(token).onSuccess { products = it }
        ApiService.getCategories(token).onSuccess { categories = it }
        refresh()
    }

    // ── YANGI XONA ──
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false; roomName = ""; roomCapacity = ""; errorMsg = "" },
            title = { Text("Yangi Xona", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = roomName, onValueChange = { roomName = it },
                        label = { Text("Xona nomi") },
                        leadingIcon = { Icon(Icons.Default.MeetingRoom, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = roomCapacity, onValueChange = { roomCapacity = it.filter { c -> c.isDigit() } },
                        label = { Text("Sig'im (kishi)") },
                        leadingIcon = { Icon(Icons.Default.Group, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    if (errorMsg.isNotEmpty()) Text(errorMsg, color = RedBusy, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (roomName.isEmpty()) { errorMsg = "Xona nomini kiriting"; return@Button }
                        scope.launch {
                            ApiService.createRoom(token, roomName, roomCapacity.toIntOrNull() ?: 4)
                                .onSuccess { showAddDialog = false; roomName = ""; roomCapacity = ""; errorMsg = ""; refresh() }
                                .onFailure { errorMsg = "Xato: ${it.message}" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { showAddDialog = false; errorMsg = "" }) { Text("Bekor") } }
        )
    }

    // ── TAHRIRLASH ──
    editRoom?.let { room ->
        var eName by remember(room) { mutableStateOf(room.name) }
        var eCapacity by remember(room) { mutableStateOf(room.capacity.toString()) }
        AlertDialog(
            onDismissRequest = { editRoom = null },
            title = { Text("Xonani tahrirlash", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = eName, onValueChange = { eName = it }, label = { Text("Xona nomi") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = eCapacity, onValueChange = { eCapacity = it.filter { c -> c.isDigit() } }, label = { Text("Sig'im") }, modifier = Modifier.fillMaxWidth(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ApiService.updateRoom(token, room.id, eName, eCapacity.toIntOrNull() ?: room.capacity)
                                .onSuccess { editRoom = null; refresh() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { editRoom = null }) { Text("Bekor") } }
        )
    }

    // ── O'CHIRISH ──
    deleteConfirmRoom?.let { room ->
        AlertDialog(
            onDismissRequest = { deleteConfirmRoom = null },
            title = { Text("O'chirish", fontWeight = FontWeight.Bold, color = RedBusy) },
            text = { Text("\"${room.name}\" xonasini o'chirishni xohlaysizmi?", color = DarkBlue) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ApiService.deleteRoom(token, room.id).onSuccess { deleteConfirmRoom = null; refresh() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedBusy)
                ) { Text("O'chirish") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteConfirmRoom = null }) { Text("Bekor") } }
        )
    }

    // ⚡ XONAGA YANGI BUYURTMA - SODDALASHTIRILGAN
    orderRoom?.let { room ->
        SimpleRoomOrderDialog(
            room = room, token = token, products = products, categories = categories,
            onDismiss = { orderRoom = null },
            onCreated = { orderRoom = null; refresh() }
        )
    }

    // ── BUYURTMANI TAHRIRLASH ──
    editOrder?.let { order ->
        OrderEditDialog(
            order = order, token = token,
            onDismiss = { editOrder = null },
            onSaved = { editOrder = null; refresh() }
        )
    }

    // ── TO'LOV ──
    checkoutOrder?.let { order ->
        CheckoutDialog(
            order = order, token = token,
            onDismiss = { checkoutOrder = null },
            onPaid = { checkoutOrder = null; refresh() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(GreenOk.copy(0.1f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text("Bo'sh: ${rooms.count { it.status == "free" }}", color = GreenOk, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
                Box(Modifier.clip(RoundedCornerShape(8.dp)).background(RedBusy.copy(0.1f)).padding(horizontal = 10.dp, vertical = 5.dp)) {
                    Text("Band: ${rooms.count { it.status == "busy" }}", color = RedBusy, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (isAdmin) {
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("+ Xona", fontSize = 12.sp) }
                }
                IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null, tint = MidBlue) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MidBlue) }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                // ── XONALAR ──
                items(rooms, key = { "rooms_${it.id}" }) { room ->
                    RoomCard(
                        room = room,
                        roomOrder = orders.firstOrNull {
                            it.room_id == room.id && it.status !in listOf("paid", "cancelled")
                        },
                        isAdmin = isAdmin,
                        onOrderClick = { orderRoom = room },
                        onEditOrderClick = { o -> editOrder = o },
                        onCheckoutClick = { o -> checkoutOrder = o },
                        onEditRoom = { editRoom = room },
                        onDeleteRoom = { deleteConfirmRoom = room }
                    )
                }
                // ── AKTIV BUYURTMALAR (xona bog'liq emas) ──
                val activeOrders = orders.filter { it.status !in listOf("paid", "cancelled") && it.room_id == null }
                if (activeOrders.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(8.dp))
                        Text("Aktiv buyurtmalar", fontWeight = FontWeight.Bold, color = OrangeWarn, fontSize = 13.sp)
                    }
                    items(activeOrders, key = { "activeOrders_${it.id}" }) { order ->
                        OrderRow(order, token,
                            onCheckout = { checkoutOrder = order },
                            onRefresh = { refresh() },
                            onEdit = { editOrder = order }
                        )
                    }
                }
                // ── BUYURTMA TARIXI ──
                val historyOrders = orders.filter { it.status in listOf("paid", "cancelled") }
                if (historyOrders.isNotEmpty()) {
                    item {
                        Spacer(Modifier.height(12.dp))
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Icon(Icons.Default.History, null, tint = TextGray, modifier = Modifier.size(16.dp))
                            Text("Buyurtmalar tarixi", fontWeight = FontWeight.Bold, color = TextGray, fontSize = 13.sp)
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    items(historyOrders.take(20), key = { "historyOrders_${it.id}" }) { order ->
                        CompactOrderRow(order)
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ══════════════════════════════════════
// XONA KARTASI - chap: + Buyurtma, o'ng: 3 nuqta
// ══════════════════════════════════════
@Composable
fun RoomCard(
    room: Room,
    roomOrder: Order?,
    isAdmin: Boolean,
    onOrderClick: () -> Unit,
    onEditOrderClick: (Order) -> Unit,
    onCheckoutClick: (Order) -> Unit,
    onEditRoom: () -> Unit,
    onDeleteRoom: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }
    val sc = when (room.status) { "free" -> GreenOk; "busy" -> RedBusy; else -> Accent }
    val st = when (room.status) { "free" -> "Bo'sh"; "busy" -> "Band"; else -> "Bron" }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (room.status == "busy") Color(0xFFFFFBEB) else Color.White
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                Arrangement.SpaceBetween, Alignment.CenterVertically
            ) {
                // Chap: nom + holat
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(
                            Icons.Default.MeetingRoom,
                            null,
                            tint = sc,
                            modifier = Modifier.size(18.dp)
                        )
                        Text(room.name, fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 16.sp)
                        Box(
                            Modifier.clip(RoundedCornerShape(6.dp))
                                .background(sc.copy(0.15f))
                                .padding(horizontal = 6.dp, vertical = 2.dp)
                        ) { Text(st, color = sc, fontWeight = FontWeight.Bold, fontSize = 11.sp) }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("👥 ${room.capacity} kishi", color = TextGray, fontSize = 12.sp)
                        if (room.status == "busy" && roomOrder != null) {
                            Text(
                                "💰 %,.0f so'm".format(roomOrder.total),
                                color = MidBlue, fontWeight = FontWeight.Bold, fontSize = 12.sp
                            )
                        }
                    }
                }
                // O'ng: Buyurtma tugmasi + 3 nuqta
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (room.status == "free") {
                        Button(
                            onClick = onOrderClick,
                            colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Buyurtma", fontSize = 11.sp)
                        }
                    } else if (room.status == "busy" && roomOrder != null) {
                        OutlinedButton(
                            onClick = { onEditOrderClick(roomOrder) },
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Edit, null, modifier = Modifier.size(12.dp), tint = Accent)
                            Spacer(Modifier.width(2.dp))
                            Text("Tahrir", fontSize = 11.sp, color = Accent)
                        }
                        Button(
                            onClick = { onCheckoutClick(roomOrder) },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Payment, null, modifier = Modifier.size(12.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("To'lov", fontSize = 11.sp)
                        }
                    }
                    if (isAdmin) {
                        Box {
                            IconButton(onClick = { showMenu = true }, modifier = Modifier.size(32.dp)) {
                                Icon(Icons.Default.MoreVert, null, tint = TextGray)
                            }
                            DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                                DropdownMenuItem(
                                    text = { Text("Tahrirlash") },
                                    leadingIcon = { Icon(Icons.Default.Edit, null, tint = MidBlue) },
                                    onClick = { onEditRoom(); showMenu = false }
                                )
                                DropdownMenuItem(
                                    text = { Text("O'chirish", color = RedBusy) },
                                    leadingIcon = { Icon(Icons.Default.Delete, null, tint = RedBusy) },
                                    onClick = { onDeleteRoom(); showMenu = false }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════
// SODDA XONA BUYURTMA DIALOGI
// (faqat to'ldirib qo'shish, status workflow YO'Q)
// ══════════════════════════════════════
@Composable
fun SimpleRoomOrderDialog(
    room: Room,
    token: String,
    products: List<Product>,
    categories: List<Category>,
    onDismiss: () -> Unit,
    onCreated: () -> Unit
) {
    var customerName by remember { mutableStateOf("") }
    var cart by remember { mutableStateOf<Map<Product, Int>>(emptyMap()) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var errorMsg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val filteredProducts = if (selectedCategoryId != null)
        products.filter { it.category_id == selectedCategoryId }
    else products

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.MeetingRoom, null, tint = MidBlue)
                Text("${room.name} — Yangi buyurtma", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 16.sp)
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item {
                    OutlinedTextField(
                        value = customerName, onValueChange = { customerName = it },
                        label = { Text("Mijoz ismi (ixtiyoriy)") },
                        leadingIcon = { Icon(Icons.Default.Person, null) },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
                // Kategoriyalar filter
                if (categories.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedCategoryId == null,
                                    onClick = { selectedCategoryId = null },
                                    label = { Text("Barchasi", fontSize = 11.sp) }
                                )
                            }
                            items(categories) { cat ->
                                FilterChip(
                                    selected = selectedCategoryId == cat.id,
                                    onClick = { selectedCategoryId = cat.id },
                                    label = { Text(cat.name, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
                item {
                    Text("Mahsulotlar:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp)
                }
                items(filteredProducts, key = { "filteredProducts_${it.id}" }) { p ->
                    CartProductRow(p, cart[p] ?: 0,
                        onAdd = { cart = cart.toMutableMap().also { it[p] = (cart[p] ?: 0) + 1 } },
                        onRemove = {
                            val cur = cart[p] ?: 0
                            cart = cart.toMutableMap().also { if (cur <= 1) it.remove(p) else it[p] = cur - 1 }
                        }
                    )
                }
                if (cart.isNotEmpty()) {
                    item {
                        Divider()
                        val total = cart.entries.sumOf { it.key.price * it.value }
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Jami:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 14.sp)
                            Text(
                                "%,.0f so'm".format(total),
                                fontWeight = FontWeight.Bold, color = MidBlue, fontSize = 15.sp
                            )
                        }
                    }
                }
                if (errorMsg.isNotEmpty()) item { Text(errorMsg, color = RedBusy, fontSize = 13.sp) }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    if (cart.isEmpty()) { errorMsg = "Savatga mahsulot qo'shing"; return@Button }
                    scope.launch {
                        loading = true
                        ApiService.createOrder(token, "dine_in", room.id, customerName)
                            .onSuccess { orderId ->
                                cart.forEach { (product, qty) ->
                                    ApiService.addOrderItem(token, orderId, product.id, qty.toFloat())
                                }
                                // ⚡ Soddalashtirildi: yaratilgan order darhol "confirmed" status,
                                // status workflow ishlatilmaydi
                                ApiService.updateOrderStatus(token, orderId, "confirmed")
                                loading = false
                                onCreated()
                            }.onFailure {
                                errorMsg = "Xato: ${it.message}"
                                loading = false
                            }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                enabled = cart.isNotEmpty() && !loading
            ) {
                if (loading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(16.dp))
                else {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Qo'shish")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Bekor") }
        }
    )
}

// ══════════════════════════════════════
// ONLINE TAB (Bot orqali)
// ══════════════════════════════════════
@Composable
fun OnlineTab(token: String) {
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showMessageDialog by remember { mutableStateOf<Order?>(null) }
    var checkoutOrder by remember { mutableStateOf<Order?>(null) }
    var editOrder by remember { mutableStateOf<Order?>(null) }
    var messageText by remember { mutableStateOf("") }
    var msgResult by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            val all = mutableListOf<Order>()
            listOf("new", "confirmed", "preparing", "ready").forEach { s ->
                ApiService.getOrders(token, s, "bot").onSuccess { all.addAll(it) }
            }
            ApiService.getOrders(token, "paid", "bot").onSuccess { all.addAll(it) }
            ApiService.getOrders(token, "cancelled", "bot").onSuccess { all.addAll(it) }
            orders = all.sortedByDescending { it.id }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    showMessageDialog?.let { order ->
        AlertDialog(
            onDismissRequest = { showMessageDialog = null; messageText = ""; msgResult = "" },
            title = { Text("💬 Mijozga xabar — #${order.id.toString().padStart(4, '0')}", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 15.sp) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Bot orqali mijozga xabar yuboring:", color = TextGray, fontSize = 13.sp) }
                    item {
                        OutlinedTextField(
                            value = messageText, onValueChange = { messageText = it },
                            label = { Text("Xabar matni") },
                            modifier = Modifier.fillMaxWidth(), minLines = 3
                        )
                    }
                    item { Text("Tez xabarlar:", color = TextGray, fontSize = 12.sp) }
                    items(listOf(
                        "✅ Buyurtmangiz tasdiqlandi!",
                        "👨‍🍳 Buyurtmangiz tayyorlanmoqda...",
                        "🔔 Buyurtmangiz tayyor, keling!",
                        "⏰ Taxminan 15-20 daqiqada tayyor bo'ladi.",
                        "❓ Iltimos, telefon raqamingizni tasdiqlang."
                    )) { preset ->
                        OutlinedButton(
                            onClick = { messageText = preset },
                            modifier = Modifier.fillMaxWidth(),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                        ) { Text(preset, fontSize = 11.sp, color = DarkBlue) }
                    }
                    if (msgResult.isNotEmpty()) {
                        item {
                            Text(
                                msgResult,
                                color = if (msgResult.contains("yuborildi")) GreenOk else RedBusy,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (messageText.isEmpty()) { msgResult = "Xabar matnini kiriting!"; return@Button }
                        scope.launch {
                            ApiService.sendMessageToCustomer(token, order.id, messageText)
                                .onSuccess { msgResult = "✅ Xabar muvaffaqiyatli yuborildi!" }
                                .onFailure { msgResult = "❌ Xato: ${it.message}" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MidBlue)
                ) { Text("Yuborish") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showMessageDialog = null; messageText = ""; msgResult = "" }) { Text("Yopish") }
            }
        )
    }

    editOrder?.let { order ->
        OrderEditDialog(
            order = order, token = token,
            onDismiss = { editOrder = null },
            onSaved = { editOrder = null; refresh() }
        )
    }

    checkoutOrder?.let { order ->
        CheckoutDialog(
            order = order, token = token,
            onDismiss = { checkoutOrder = null },
            onPaid = { checkoutOrder = null; refresh() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Column {
                Text("Online Buyurtmalar", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
                val active = orders.count { it.status in listOf("new", "confirmed", "preparing", "ready") }
                if (active > 0) Text("$active ta faol", color = OrangeWarn, fontSize = 12.sp)
            }
            IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null, tint = MidBlue) }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MidBlue) }
            orders.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.SmartToy, null, tint = TextGray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Bot orqali buyurtma yo'q", color = TextGray)
                }
            }
            else -> {
                val activeOrders = orders.filter { it.status !in listOf("paid", "cancelled") }
                val historyOrders = orders.filter { it.status in listOf("paid", "cancelled") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeOrders.isNotEmpty()) {
                        items(activeOrders, key = { "activeOrders_${it.id}" }) { order ->
                            OrderRow(
                                order = order, token = token,
                                onCheckout = { checkoutOrder = order },
                                onRefresh = { refresh() },
                                onSendMessage = { showMessageDialog = order },
                                onEdit = { editOrder = order }
                            )
                        }
                    }
                    if (historyOrders.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.History, null, tint = TextGray, modifier = Modifier.size(16.dp))
                                Text("Buyurtmalar tarixi", fontWeight = FontWeight.Bold, color = TextGray, fontSize = 13.sp)
                            }
                        }
                        items(historyOrders.take(20), key = { "historyOrders_${it.id}" }) { order ->
                            CompactOrderRow(order)
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

// ══════════════════════════════════════
// OLIB KETISH TAB
// ══════════════════════════════════════
@Composable
fun TakeawayTab(token: String) {
    var orders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var cart by remember { mutableStateOf<Map<Product, Int>>(emptyMap()) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var customerNote by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    var checkoutOrder by remember { mutableStateOf<Order?>(null) }
    var editOrder by remember { mutableStateOf<Order?>(null) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            val all = mutableListOf<Order>()
            listOf("new", "confirmed", "preparing", "ready").forEach { s ->
                ApiService.getOrders(token, s, "takeaway").onSuccess { all.addAll(it) }
            }
            ApiService.getOrders(token, "paid", "takeaway").onSuccess { all.addAll(it) }
            ApiService.getOrders(token, "cancelled", "takeaway").onSuccess { all.addAll(it) }
            orders = all.sortedByDescending { it.id }
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        ApiService.getProducts(token).onSuccess { products = it }
        ApiService.getCategories(token).onSuccess { categories = it }
        refresh()
    }

    val filteredProducts = if (selectedCategoryId != null)
        products.filter { it.category_id == selectedCategoryId }
    else products

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false; cart = emptyMap()
                customerName = ""; customerPhone = ""; customerNote = ""; errorMsg = ""
            },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.ShoppingBag, null, tint = MidBlue)
                    Text("Yangi Olib Ketish", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 16.sp)
                }
            },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedTextField(
                            value = customerName, onValueChange = { customerName = it },
                            label = { Text("Mijoz ismi") },
                            leadingIcon = { Icon(Icons.Default.Person, null) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = customerPhone, onValueChange = { customerPhone = it },
                            label = { Text("Telefon raqam") },
                            leadingIcon = { Icon(Icons.Default.Phone, null) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = customerNote, onValueChange = { customerNote = it },
                            label = { Text("Izoh (ixtiyoriy)") },
                            leadingIcon = { Icon(Icons.Default.Notes, null) },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    if (categories.isNotEmpty()) {
                        item {
                            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                item {
                                    FilterChip(
                                        selected = selectedCategoryId == null,
                                        onClick = { selectedCategoryId = null },
                                        label = { Text("Barchasi", fontSize = 11.sp) }
                                    )
                                }
                                items(categories) { cat ->
                                    FilterChip(
                                        selected = selectedCategoryId == cat.id,
                                        onClick = { selectedCategoryId = cat.id },
                                        label = { Text(cat.name, fontSize = 11.sp) }
                                    )
                                }
                            }
                        }
                    }
                    item { Text("Mahsulotlar:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp) }
                    items(filteredProducts, key = { "filteredProducts_${it.id}" }) { p ->
                        CartProductRow(p, cart[p] ?: 0,
                            onAdd = { cart = cart.toMutableMap().also { it[p] = (cart[p] ?: 0) + 1 } },
                            onRemove = {
                                val cur = cart[p] ?: 0
                                cart = cart.toMutableMap().also {
                                    if (cur <= 1) it.remove(p) else it[p] = cur - 1
                                }
                            }
                        )
                    }
                    if (cart.isNotEmpty()) {
                        item {
                            Divider()
                            val total = cart.entries.sumOf { it.key.price * it.value }
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Text("Jami:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 14.sp)
                                Text("%,.0f so'm".format(total), fontWeight = FontWeight.Bold, color = MidBlue, fontSize = 15.sp)
                            }
                        }
                    }
                    if (errorMsg.isNotEmpty()) item { Text(errorMsg, color = RedBusy, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (cart.isEmpty()) { errorMsg = "Savat bo'sh!"; return@Button }
                        scope.launch {
                            ApiService.createOrder(
                                token, "takeaway",
                                customerName = customerName,
                                customerPhone = customerPhone,
                                note = customerNote
                            ).onSuccess { orderId ->
                                cart.forEach { (product, qty) ->
                                    ApiService.addOrderItem(token, orderId, product.id, qty.toFloat())
                                }
                                ApiService.updateOrderStatus(token, orderId, "confirmed")
                                showAddDialog = false; cart = emptyMap()
                                customerName = ""; customerPhone = ""; customerNote = ""; errorMsg = ""
                                refresh()
                            }.onFailure { errorMsg = "Xato: ${it.message}" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                    enabled = cart.isNotEmpty()
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Qo'shish")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showAddDialog = false; cart = emptyMap()
                    customerName = ""; customerPhone = ""; customerNote = ""; errorMsg = ""
                }) { Text("Bekor") }
            }
        )
    }

    editOrder?.let { order ->
        OrderEditDialog(
            order = order, token = token,
            onDismiss = { editOrder = null },
            onSaved = { editOrder = null; refresh() }
        )
    }

    checkoutOrder?.let { order ->
        CheckoutDialog(
            order = order, token = token,
            onDismiss = { checkoutOrder = null },
            onPaid = { checkoutOrder = null; refresh() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Olib Ketish", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                    Spacer(Modifier.width(2.dp))
                    Text("Yangi", fontSize = 12.sp)
                }
                IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null, tint = MidBlue) }
            }
        }
        Spacer(Modifier.height(8.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { CircularProgressIndicator(color = MidBlue) }
            orders.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.ShoppingBag, null, tint = TextGray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Olib ketish buyurtmasi yo'q", color = TextGray)
                }
            }
            else -> {
                val activeOrders = orders.filter { it.status !in listOf("paid", "cancelled") }
                val historyOrders = orders.filter { it.status in listOf("paid", "cancelled") }
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (activeOrders.isNotEmpty()) {
                        items(activeOrders, key = { "activeOrders_${it.id}" }) { order ->
                            OrderRow(order, token,
                                onCheckout = { checkoutOrder = order },
                                onRefresh = { refresh() },
                                onEdit = { editOrder = order }
                            )
                        }
                    }
                    if (historyOrders.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Icon(Icons.Default.History, null, tint = TextGray, modifier = Modifier.size(16.dp))
                                Text("Buyurtmalar tarixi", fontWeight = FontWeight.Bold, color = TextGray, fontSize = 13.sp)
                            }
                        }
                        items(historyOrders.take(20), key = { "historyOrders_${it.id}" }) { order ->
                            CompactOrderRow(order)
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

// ══════════════════════════════════════
// UMUMIY BUYURTMA SATRI - sodda
// ══════════════════════════════════════
@Composable
fun OrderRow(
    order: Order,
    token: String,
    onCheckout: () -> Unit,
    onRefresh: () -> Unit,
    onSendMessage: (() -> Unit)? = null,
    onEdit: (() -> Unit)? = null
) {
    val scope = rememberCoroutineScope()
    val isPaid = order.status in listOf("paid", "cancelled")
    val statusColor = when (order.status) {
        "new" -> OrangeWarn; "confirmed" -> MidBlue; "preparing" -> Purple
        "ready" -> GreenOk; "paid" -> TextGray; "cancelled" -> RedBusy; else -> TextGray
    }
    val statusText = when (order.status) {
        "new" -> "Yangi"; "confirmed" -> "Tasdiqlandi"
        "preparing" -> "Tayyorlanmoqda"; "ready" -> "Tayyor"
        "paid" -> "To'landi"; "cancelled" -> "Bekor qilindi"; else -> order.status
    }
    val typeIcon = when (order.order_type) {
        "dine_in" -> Icons.Default.Chair
        "takeaway" -> Icons.Default.ShoppingBag
        "bot" -> Icons.Default.SmartToy
        else -> Icons.Default.Receipt
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (order.status == "new") Color(0xFFFFFBEB) else Color.White
        ),
        elevation = CardDefaults.cardElevation(2.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Icon(typeIcon, null, tint = MidBlue, modifier = Modifier.size(16.dp))
                    Text("#${order.id.toString().padStart(4, '0')}", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 15.sp)
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(statusColor.copy(0.15f)).padding(horizontal = 6.dp, vertical = 2.dp)) {
                        Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
                Text(order.created_at.take(16).replace("T", " "), color = TextGray, fontSize = 10.sp)
            }
            if (!order.customer_name.isNullOrEmpty() || !order.customer_phone.isNullOrEmpty()) {
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (!order.customer_name.isNullOrEmpty())
                        Text("👤 ${order.customer_name}", color = TextGray, fontSize = 12.sp)
                    if (!order.customer_phone.isNullOrEmpty())
                        Text("📱 ${order.customer_phone}", color = TextGray, fontSize = 12.sp)
                }
            }
            if (!order.note.isNullOrEmpty()) Text("💬 ${order.note}", color = DarkBlue, fontSize = 12.sp)

            // Mahsulotlar (qisqacha)
            if (order.items.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                order.items.take(3).forEach { item ->
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("• ${item.product_name} x${item.quantity.toInt()}", color = TextGray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("%,.0f".format(item.total_price), color = TextGray, fontSize = 11.sp)
                    }
                }
                if (order.items.size > 3) {
                    Text("... va yana ${order.items.size - 3} ta", color = TextGray, fontSize = 11.sp)
                }
            }

            Spacer(Modifier.height(6.dp))
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text("%,.0f so'm".format(order.total), fontWeight = FontWeight.Bold, color = MidBlue, fontSize = 16.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!isPaid && onEdit != null) {
                        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Edit, "Tahrirlash", tint = Accent, modifier = Modifier.size(18.dp))
                        }
                    }
                    if (!isPaid && onSendMessage != null) {
                        IconButton(onClick = onSendMessage, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.AutoMirrored.Filled.Send, "Xabar", tint = MidBlue, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }

            // ⚡ TUGMALAR - faqat asosiy: Tahrirlash, To'lov, Bekor qilish
            if (!isPaid) {
                Spacer(Modifier.height(8.dp))
                if (order.order_type == "bot" && order.status == "new") {
                    // Bot uchun: Qabul qilish/Rad qilish
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = { scope.launch { ApiService.updateOrderStatus(token, order.id, "confirmed"); onRefresh() } },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Qabul", fontSize = 12.sp)
                        }
                        OutlinedButton(
                            onClick = { scope.launch { ApiService.updateOrderStatus(token, order.id, "cancelled"); onRefresh() } },
                            modifier = Modifier.weight(1f), contentPadding = PaddingValues(vertical = 6.dp)
                        ) {
                            Icon(Icons.Default.Close, null, tint = RedBusy, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(2.dp))
                            Text("Rad", fontSize = 12.sp, color = RedBusy)
                        }
                    }
                } else {
                    // Boshqa hollarda: To'lov tugmasi
                    Button(
                        onClick = onCheckout,
                        colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = 8.dp)
                    ) {
                        Icon(Icons.Default.Payment, null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("To'lov va Chek", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════
// QISQA BUYURTMA SATRI (TARIX UCHUN)
// Bosilganda ochilib, to'liq ko'rinadi
// ══════════════════════════════════════
@Composable
fun CompactOrderRow(order: Order) {
    var expanded by remember { mutableStateOf(false) }
    val statusColor = when (order.status) {
        "paid" -> GreenOk; "cancelled" -> RedBusy; else -> TextGray
    }
    val statusText = when (order.status) {
        "paid" -> "✓ To'landi"; "cancelled" -> "✗ Bekor"; else -> order.status
    }
    Card(
        modifier = Modifier.fillMaxWidth().clickable { expanded = !expanded },
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFF8FAFC)),
        elevation = CardDefaults.cardElevation(0.dp)
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("#${order.id.toString().padStart(4,'0')}", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 12.sp)
                    Text(
                        order.customer_name?.takeIf { it.isNotEmpty() } ?: "Mijoz",
                        color = DarkBlue, fontSize = 12.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, false)
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("%,.0f so'm".format(order.total), fontWeight = FontWeight.Bold, color = MidBlue, fontSize = 12.sp)
                    Text(order.created_at.take(10), color = TextGray, fontSize = 10.sp)
                    Icon(
                        if (expanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        null, tint = TextGray, modifier = Modifier.size(16.dp)
                    )
                }
            }
            if (expanded) {
                Spacer(Modifier.height(6.dp))
                Divider(color = Color.LightGray.copy(0.5f))
                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                    Text("Holat:", color = TextGray, fontSize = 11.sp)
                    Text(statusText, color = statusColor, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
                if (!order.customer_phone.isNullOrEmpty()) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Telefon:", color = TextGray, fontSize = 11.sp)
                        Text(order.customer_phone, color = DarkBlue, fontSize = 11.sp)
                    }
                }
                if (!order.note.isNullOrEmpty()) {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Izoh:", color = TextGray, fontSize = 11.sp)
                        Text(order.note, color = DarkBlue, fontSize = 11.sp, modifier = Modifier.weight(1f), textAlign = TextAlign.End)
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text("Tarkib:", color = TextGray, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                order.items.forEach { item ->
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("• ${item.product_name} x${item.quantity.toInt()}", color = TextGray, fontSize = 11.sp, modifier = Modifier.weight(1f))
                        Text("%,.0f".format(item.total_price), color = DarkBlue, fontSize = 11.sp)
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════
// BUYURTMA TAHRIRLASH DIALOGI
// ══════════════════════════════════════
@Composable
fun OrderEditDialog(order: Order, token: String, onDismiss: () -> Unit, onSaved: () -> Unit) {
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var currentItems by remember { mutableStateOf(order.items) }
    var cart by remember { mutableStateOf<Map<Product, Int>>(emptyMap()) }
    var extraDesc by remember { mutableStateOf("") }
    var extraAmount by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        ApiService.getProducts(token).onSuccess { products = it }
        ApiService.getCategories(token).onSuccess { categories = it }
        ApiService.getOrder(token, order.id).onSuccess { updated -> currentItems = updated.items }
    }

    val filteredProducts = if (selectedCategoryId != null)
        products.filter { it.category_id == selectedCategoryId }
    else products

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Edit, null, tint = Accent)
                Text("Buyurtma #${order.id.toString().padStart(4, '0')}", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 15.sp)
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (currentItems.isNotEmpty()) {
                    item {
                        Text("Mavjud tarkib:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp)
                    }
                    items(currentItems, key = { "currentItems_${it.id}" }) { item ->
                        Row(
                            Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp))
                                .background(Color(0xFFF8FAFC)).padding(8.dp),
                            Arrangement.SpaceBetween, Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.product_name, fontSize = 13.sp, color = DarkBlue)
                                Text(
                                    "x${item.quantity.toInt()} — %,.0f so'm".format(item.total_price),
                                    fontSize = 11.sp, color = TextGray
                                )
                            }
                            IconButton(
                                onClick = {
                                    scope.launch {
                                        ApiService.removeOrderItem(token, order.id, item.id)
                                            .onSuccess {
                                                currentItems = currentItems.filter { it.id != item.id }
                                            }.onFailure { errorMsg = "O'chirishda xato" }
                                    }
                                },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.Delete, null, tint = RedBusy, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                    item { Divider() }
                }

                item { Text("Mahsulot qo'shish:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp) }
                if (categories.isNotEmpty()) {
                    item {
                        LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            item {
                                FilterChip(
                                    selected = selectedCategoryId == null,
                                    onClick = { selectedCategoryId = null },
                                    label = { Text("Barchasi", fontSize = 11.sp) }
                                )
                            }
                            items(categories) { cat ->
                                FilterChip(
                                    selected = selectedCategoryId == cat.id,
                                    onClick = { selectedCategoryId = cat.id },
                                    label = { Text(cat.name, fontSize = 11.sp) }
                                )
                            }
                        }
                    }
                }
                items(filteredProducts, key = { "filteredProducts_${it.id}" }) { p ->
                    CartProductRow(p, cart[p] ?: 0,
                        onAdd = { cart = cart.toMutableMap().also { it[p] = (cart[p] ?: 0) + 1 } },
                        onRemove = {
                            val cur = cart[p] ?: 0
                            cart = cart.toMutableMap().also { if (cur <= 1) it.remove(p) else it[p] = cur - 1 }
                        }
                    )
                }
                if (cart.isNotEmpty()) {
                    item {
                        Button(
                            onClick = {
                                scope.launch {
                                    cart.forEach { (product, qty) ->
                                        ApiService.addOrderItem(token, order.id, product.id, qty.toFloat())
                                    }
                                    cart = emptyMap()
                                    ApiService.getOrder(token, order.id).onSuccess { updated ->
                                        currentItems = updated.items
                                    }
                                    errorMsg = "Mahsulotlar qo'shildi"
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Add, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Savatni qo'shish")
                        }
                    }
                }

                item { Divider() }
                item { Text("Qo'shimcha haq:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp) }
                item {
                    OutlinedTextField(
                        value = extraDesc, onValueChange = { extraDesc = it },
                        label = { Text("Tavsif (xizmat haqi va h.k.)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
                item {
                    OutlinedTextField(
                        value = extraAmount, onValueChange = { extraAmount = it.filter { c -> c.isDigit() } },
                        label = { Text("Summa (so'm)") },
                        modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
                if (extraDesc.isNotEmpty() && extraAmount.isNotEmpty()) {
                    item {
                        Button(
                            onClick = {
                                val amount = extraAmount.toDoubleOrNull() ?: 0.0
                                if (amount <= 0) { errorMsg = "Summani kiriting"; return@Button }
                                scope.launch {
                                    ApiService.addExtraCharge(token, order.id, extraDesc, amount)
                                        .onSuccess {
                                            extraDesc = ""; extraAmount = ""
                                            errorMsg = "Qo'shildi"
                                            ApiService.getOrder(token, order.id).onSuccess { updated ->
                                                currentItems = updated.items
                                            }
                                        }.onFailure { errorMsg = "Xato: ${it.message}" }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("Haq qo'shish") }
                    }
                }

                if (errorMsg.isNotEmpty()) {
                    item {
                        Text(
                            errorMsg,
                            color = if (errorMsg.contains("qo'shildi") || errorMsg == "Qo'shildi") GreenOk else RedBusy,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onSaved() },
                colors = ButtonDefaults.buttonColors(containerColor = MidBlue)
            ) { Text("Tayyor") }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Yopish") }
        }
    )
}

// ══════════════════════════════════════
// CHEKOUT (TO'LOV) DIALOGI
// ══════════════════════════════════════
@Composable
fun CheckoutDialog(
    order: Order,
    token: String,
    onDismiss: () -> Unit,
    onPaid: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedMethod by remember { mutableStateOf("cash") }
    var showReceipt by remember { mutableStateOf(false) }
    var receiptData by remember { mutableStateOf<PaymentResult?>(null) }
    var errorMsg by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var orderDetail by remember { mutableStateOf(order) }

    LaunchedEffect(order.id) {
        ApiService.getOrder(token, order.id).onSuccess { orderDetail = it }
    }

    if (showReceipt && receiptData != null) {
        ReceiptDialog(
            receipt = receiptData!!,
            onDismiss = { onPaid() }
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(Icons.Default.Payment, null, tint = GreenOk)
                Text("Buyurtma #${order.id.toString().padStart(4, '0')}", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 16.sp)
            }
        },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (orderDetail.items.isNotEmpty()) {
                    item { Text("Tarkib:", fontWeight = FontWeight.Bold, color = DarkBlue) }
                    items(orderDetail.items) { item ->
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text(
                                "${item.product_name} × ${item.quantity.toInt()}",
                                color = TextGray, fontSize = 13.sp, modifier = Modifier.weight(1f)
                            )
                            Text(
                                "%,.0f so'm".format(item.total_price),
                                color = DarkBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    item { Divider() }
                }
                item {
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("Jami:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 16.sp)
                        Text(
                            "%,.0f so'm".format(orderDetail.total),
                            fontWeight = FontWeight.Bold, color = MidBlue, fontSize = 16.sp
                        )
                    }
                }
                item { Spacer(Modifier.height(4.dp)) }
                item { Text("To'lov usuli:", fontWeight = FontWeight.Bold, color = DarkBlue) }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf(
                            Triple("cash", "Naqd", Icons.Default.Money),
                            Triple("card", "Karta", Icons.Default.CreditCard),
                            Triple("click", "Click", Icons.Default.Smartphone)
                        ).forEach { (k, l, ic) ->
                            Button(
                                onClick = { selectedMethod = k },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedMethod == k) MidBlue else Color.LightGray
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                            ) {
                                Icon(ic, null, modifier = Modifier.size(14.dp), tint = if (selectedMethod == k) Color.White else DarkBlue)
                                Spacer(Modifier.width(2.dp))
                                Text(l, fontSize = 12.sp, color = if (selectedMethod == k) Color.White else DarkBlue)
                            }
                        }
                    }
                }
                if (errorMsg.isNotEmpty()) {
                    item { Text(errorMsg, color = RedBusy, fontSize = 13.sp) }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    loading = true; errorMsg = ""
                    scope.launch {
                        ApiService.payOrder(token, orderDetail.id, selectedMethod, orderDetail.total)
                            .onSuccess { result ->
                                receiptData = result
                                showReceipt = true
                                loading = false
                            }
                            .onFailure {
                                errorMsg = "Xato: ${it.message}"
                                loading = false
                            }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = GreenOk),
                enabled = !loading
            ) {
                if (loading) CircularProgressIndicator(color = Color.White, modifier = Modifier.size(18.dp))
                else {
                    Icon(Icons.Default.Receipt, null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("To'lash va Chek")
                }
            }
        },
        dismissButton = {
            OutlinedButton(onClick = onDismiss) { Text("Yopish") }
        }
    )
}

// ══════════════════════════════════════
// CHEK (RECEIPT) DIALOGI - PDF chiqish bilan
// ══════════════════════════════════════
@Composable
fun ReceiptDialog(receipt: PaymentResult, onDismiss: () -> Unit) {
    val df = SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault())
    val dateStr = try { df.format(Date()) } catch (e: Exception) { receipt.paid_at }
    val methodText = when (receipt.method) {
        "cash" -> "Naqd pul"; "card" -> "Plastik karta"
        "click" -> "Click"; "payme" -> "Payme"; else -> receipt.method
    }
    val context = LocalContext.current

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            LazyColumn(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                item {
                    Box(
                        Modifier.size(56.dp).clip(CircleShape).background(GreenOk),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(32.dp))
                    }
                    Spacer(Modifier.height(12.dp))
                    Text("To'lov amalga oshirildi!", color = GreenOk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("CORTEX POS", fontWeight = FontWeight.Bold, fontSize = 20.sp, color = DarkBlue)
                    Text("Restoran cheki", color = TextGray, fontSize = 12.sp)
                    Spacer(Modifier.height(4.dp))
                    Text(dateStr, color = TextGray, fontSize = 11.sp)
                    Text(
                        "Buyurtma #${receipt.order_id.toString().padStart(4, '0')}",
                        color = TextGray, fontSize = 11.sp
                    )
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                }
                items(receipt.items) { item ->
                    Row(
                        Modifier.fillMaxWidth().padding(vertical = 3.dp),
                        Arrangement.SpaceBetween
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.product_name, fontSize = 13.sp, color = DarkBlue)
                            Text(
                                "%,.0f × ${item.quantity.toInt()}".format(item.unit_price),
                                fontSize = 11.sp, color = TextGray
                            )
                        }
                        Text(
                            "%,.0f so'm".format(item.total_price),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = DarkBlue
                        )
                    }
                }
                item {
                    Spacer(Modifier.height(8.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    if (receipt.discount > 0) {
                        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                            Text("Chegirma:", color = TextGray, fontSize = 13.sp)
                            Text(
                                "-%,.0f so'm".format(receipt.discount),
                                color = RedBusy, fontSize = 13.sp
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("JAMI:", fontWeight = FontWeight.Bold, fontSize = 16.sp, color = DarkBlue)
                        Text(
                            "%,.0f so'm".format(receipt.total),
                            fontWeight = FontWeight.Bold, fontSize = 16.sp, color = MidBlue
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                        Text("To'lov:", color = TextGray, fontSize = 13.sp)
                        Text(methodText, color = DarkBlue, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                    }
                    Spacer(Modifier.height(12.dp))
                    Divider()
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Xaridingiz uchun rahmat! 🙏",
                        color = GreenOk, fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                    Spacer(Modifier.height(16.dp))

                    // ⚡ PDF YUBORISH va YOPISH tugmalari
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // PDF saqlash va ulashish
                        Button(
                            onClick = {
                                shareReceiptAsPdf(context, receipt, dateStr, methodText)
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = Accent),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.PictureAsPdf, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("PDF Yuborish", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = onDismiss,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)
                        ) {
                            Icon(Icons.Default.Check, null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("Yopish", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }
    }
}

// ⚡ Yaxshilangan PDF generator
fun shareReceiptAsPdf(context: Context, receipt: PaymentResult, dateStr: String, methodText: String) {
    try {
        val pdfDoc = PdfDocument()
        // 80mm chek qog'oziga moslab katta o'lchamda
        val pageWidth = 380
        val itemHeight = 32
        val pageHeight = 250 + receipt.items.size * itemHeight + (if (receipt.discount > 0) 24 else 0)
        val pageInfo = PdfDocument.PageInfo.Builder(pageWidth, pageHeight, 1).create()
        val page = pdfDoc.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply {
            textSize = 22f; isFakeBoldText = true; typeface = Typeface.DEFAULT_BOLD
        }
        val subtitlePaint = Paint().apply { textSize = 13f; color = android.graphics.Color.GRAY }
        val itemPaint = Paint().apply { textSize = 14f }
        val itemBoldPaint = Paint().apply { textSize = 14f; isFakeBoldText = true }
        val totalPaint = Paint().apply {
            textSize = 18f; isFakeBoldText = true; typeface = Typeface.DEFAULT_BOLD
        }
        val linePaint = Paint().apply {
            color = android.graphics.Color.LTGRAY; strokeWidth = 1f
        }

        var y = 35f
        // Header
        canvas.drawText("CORTEX POS", pageWidth / 2f - 75f, y, titlePaint); y += 22f
        canvas.drawText("Restoran cheki", pageWidth / 2f - 60f, y, subtitlePaint); y += 18f
        canvas.drawText(dateStr, pageWidth / 2f - 50f, y, subtitlePaint); y += 16f
        canvas.drawText(
            "Buyurtma #${receipt.order_id.toString().padStart(4, '0')}",
            pageWidth / 2f - 60f, y, subtitlePaint
        ); y += 18f

        // Divider
        canvas.drawLine(15f, y, pageWidth - 15f, y, linePaint); y += 14f

        // Items
        receipt.items.forEach { item ->
            val name = if (item.product_name.length > 28) item.product_name.take(26) + ".." else item.product_name
            canvas.drawText(name, 15f, y, itemBoldPaint); y += 14f
            val line = "  %d × %,.0f = %,.0f so'm".format(
                item.quantity.toInt(), item.unit_price, item.total_price
            )
            canvas.drawText(line, 15f, y, itemPaint); y += 16f
        }

        canvas.drawLine(15f, y, pageWidth - 15f, y, linePaint); y += 18f

        if (receipt.discount > 0) {
            canvas.drawText("Chegirma: -%,.0f so'm".format(receipt.discount), 15f, y, itemPaint); y += 18f
        }

        canvas.drawText("JAMI: %,.0f so'm".format(receipt.total), 15f, y, totalPaint); y += 22f
        canvas.drawText("To'lov: $methodText", 15f, y, itemPaint); y += 18f

        canvas.drawLine(15f, y, pageWidth - 15f, y, linePaint); y += 20f
        canvas.drawText("Xaridingiz uchun rahmat!", pageWidth / 2f - 90f, y, itemBoldPaint)

        pdfDoc.finishPage(page)

        val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
        if (dir != null && !dir.exists()) dir.mkdirs()
        val file = File(dir, "chek_${receipt.order_id}.pdf")
        pdfDoc.writeTo(FileOutputStream(file))
        pdfDoc.close()

        val uri = androidx.core.content.FileProvider.getUriForFile(
            context, "${context.packageName}.provider", file
        )
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Chek #${receipt.order_id}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Chekni yuborish"))
    } catch (e: Exception) {
        // Fallback - text format
        val text = buildString {
            appendLine("═══ CORTEX POS ═══")
            appendLine("Buyurtma #${receipt.order_id}")
            appendLine(dateStr)
            appendLine()
            receipt.items.forEach {
                appendLine("${it.product_name}: %,.0f".format(it.total_price))
            }
            appendLine()
            appendLine("JAMI: %,.0f so'm".format(receipt.total))
            appendLine("To'lov: $methodText")
        }
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        context.startActivity(Intent.createChooser(sendIntent, "Chekni ulashish"))
    }
}

// ══════════════════════════════════════════════
// STATISTIKA
// ══════════════════════════════════════════════
@Composable
fun StatisticsScreen(token: String) {
    var stats by remember { mutableStateOf<Statistics?>(null) }
    var period by remember { mutableStateOf("today") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(period) {
        loading = true
        ApiService.getStatistics(token, period).onSuccess { stats = it }
        loading = false
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Text("Statistika", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("today" to "Bugun", "week" to "Hafta", "month" to "Oy").forEach { (k, l) ->
                Button(
                    onClick = { period = k },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (period == k) MidBlue else Color.LightGray
                    ),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) { Text(l, fontSize = 12.sp, color = if (period == k) Color.White else DarkBlue) }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MidBlue)
            }
            stats == null -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Ma'lumot yuklanmadi", color = TextGray)
            }
            else -> stats?.let { s ->
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        Card(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), CardDefaults.cardColors(containerColor = MidBlue)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.AttachMoney, null, tint = Color.White)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Jami Tushum", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("%,.0f so'm".format(s.total_revenue), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), CardDefaults.cardColors(containerColor = DarkBlue)) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Receipt, null, tint = Color.White)
                                    Spacer(Modifier.width(4.dp))
                                    Text("Buyurtmalar", color = Color.White.copy(alpha = 0.9f), fontSize = 13.sp)
                                }
                                Spacer(Modifier.height(4.dp))
                                Text("${s.total_orders} ta", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                                if (s.total_orders > 0)
                                    Text("O'rtacha: %,.0f so'm".format(s.avg_order), color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("Buyurtma turlari", fontWeight = FontWeight.Bold, color = DarkBlue)
                                Spacer(Modifier.height(8.dp))
                                StatRow("🍽 Zalda", "${s.dine_in_orders} ta")
                                StatRow("🥡 Olib ketish", "${s.takeaway_orders} ta")
                                StatRow("🤖 Bot", "${s.bot_orders} ta")
                            }
                        }
                    }
                    item {
                        Card(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), CardDefaults.cardColors(containerColor = Color.White)) {
                            Column(Modifier.padding(16.dp)) {
                                Text("To'lov usullari", fontWeight = FontWeight.Bold, color = DarkBlue)
                                Spacer(Modifier.height(8.dp))
                                StatRow("💵 Naqd", "%,.0f so'm".format(s.cash))
                                StatRow("💳 Karta", "%,.0f so'm".format(s.card))
                                StatRow("📱 Online", "%,.0f so'm".format(s.online))
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }
}

@Composable
fun StatRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), Arrangement.SpaceBetween) {
        Text(label, color = TextGray, fontSize = 13.sp)
        Text(value, color = DarkBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
    }
}

// ══════════════════════════════════════════════
// TO'LOV (PAYMENT) - To'lov tarixi ham bor
// ══════════════════════════════════════════════
@Composable
fun PaymentScreen(token: String) {
    var activeOrders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var historyOrders by remember { mutableStateOf<List<Order>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var checkoutOrder by remember { mutableStateOf<Order?>(null) }
    var selectedTab by remember { mutableStateOf(0) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            loading = true
            val active = mutableListOf<Order>()
            listOf("new", "confirmed", "preparing", "ready").forEach { s ->
                ApiService.getOrders(token, s).onSuccess { active.addAll(it) }
            }
            activeOrders = active.sortedByDescending { it.id }
            val history = mutableListOf<Order>()
            ApiService.getOrders(token, "paid").onSuccess { history.addAll(it) }
            ApiService.getOrders(token, "cancelled").onSuccess { history.addAll(it) }
            historyOrders = history.sortedByDescending { it.id }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    checkoutOrder?.let { order ->
        CheckoutDialog(
            order = order, token = token,
            onDismiss = { checkoutOrder = null },
            onPaid = { checkoutOrder = null; refresh() }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray)) {
        // Tabs: Aktiv | Tarix
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = Color.White,
            contentColor = DarkBlue
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { selectedTab = 0 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.Payment, null, modifier = Modifier.size(16.dp))
                        Text("Aktiv (${activeOrders.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { selectedTab = 1 },
                text = {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Icon(Icons.Default.History, null, modifier = Modifier.size(16.dp))
                        Text("Tarix (${historyOrders.size})", fontSize = 13.sp, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }

        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Text(
                    if (selectedTab == 0) "To'lov kutayotgan" else "To'lov tarixi",
                    fontSize = 16.sp, fontWeight = FontWeight.Bold, color = DarkBlue
                )
                IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null, tint = MidBlue) }
            }
            Spacer(Modifier.height(8.dp))
            when {
                loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                    CircularProgressIndicator(color = MidBlue)
                }
                selectedTab == 0 -> {
                    if (activeOrders.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.Done, null, tint = TextGray, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("To'lov kutayotgan buyurtma yo'q", color = TextGray)
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            items(activeOrders, key = { "activeOrders_${it.id}" }) { order ->
                                OrderRow(order, token,
                                    onCheckout = { checkoutOrder = order },
                                    onRefresh = { refresh() }
                                )
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
                selectedTab == 1 -> {
                    if (historyOrders.isEmpty()) {
                        Box(Modifier.fillMaxSize(), Alignment.Center) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Default.History, null, tint = TextGray, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("To'lov tarixi bo'sh", color = TextGray)
                            }
                        }
                    } else {
                        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            items(historyOrders, key = { "historyOrders_${it.id}" }) { order ->
                                CompactOrderRow(order)
                            }
                            item { Spacer(Modifier.height(8.dp)) }
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════
// MENYU - IXCHAMLASHTIRILGAN
// ══════════════════════════════════════════════
@Composable
fun MenuScreen(token: String, userRole: String = "") {
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var categories by remember { mutableStateOf<List<Category>>(emptyList()) }
    var selectedCategoryId by remember { mutableStateOf<Int?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showAddProduct by remember { mutableStateOf(false) }
    var showAddCategory by remember { mutableStateOf(false) }
    var editProduct by remember { mutableStateOf<Product?>(null) }
    var deleteProduct by remember { mutableStateOf<Product?>(null) }
    var pName by remember { mutableStateOf("") }
    var pPrice by remember { mutableStateOf("") }
    var pUnit by remember { mutableStateOf("piece") }
    var pCategoryId by remember { mutableStateOf<Int?>(null) }
    var catName by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val isAdmin = userRole == "admin" || userRole == "super_admin"

    fun refresh() {
        scope.launch {
            ApiService.getCategories(token).onSuccess { categories = it }
            ApiService.getProducts(token).onSuccess { products = it }
        }
    }

    LaunchedEffect(Unit) {
        ApiService.getCategories(token).onSuccess {
            categories = it
            if (it.isNotEmpty()) pCategoryId = it.first().id
        }
        ApiService.getProducts(token).onSuccess { products = it }
        loading = false
    }

    val filteredProducts = if (selectedCategoryId != null)
        products.filter { it.category_id == selectedCategoryId }
    else products

    // ── YANGI KATEGORIYA ──
    if (showAddCategory) {
        AlertDialog(
            onDismissRequest = { showAddCategory = false; catName = ""; errorMsg = "" },
            title = { Text("Yangi Kategoriya", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = catName, onValueChange = { catName = it },
                        label = { Text("Kategoriya nomi") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    if (errorMsg.isNotEmpty()) Text(errorMsg, color = RedBusy, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (catName.isEmpty()) { errorMsg = "Nom kiriting"; return@Button }
                        scope.launch {
                            ApiService.createCategory(token, catName)
                                .onSuccess { showAddCategory = false; catName = ""; errorMsg = ""; refresh() }
                                .onFailure { errorMsg = "Xato: ${it.message}" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { showAddCategory = false; errorMsg = "" }) { Text("Bekor") } }
        )
    }

    // ── YANGI MAHSULOT ──
    if (showAddProduct) {
        AlertDialog(
            onDismissRequest = { showAddProduct = false; pName = ""; pPrice = ""; errorMsg = "" },
            title = { Text("Yangi Mahsulot", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedTextField(
                            value = pName, onValueChange = { pName = it },
                            label = { Text("Nomi") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = pPrice, onValueChange = { pPrice = it.filter { c -> c.isDigit() } },
                            label = { Text("Narxi (so'm)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    item {
                        Text("O'lchov birligi:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp)
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("piece" to "Dona", "kg" to "Kg", "liter" to "Litr", "portion" to "Porsiya").forEach { (k, l) ->
                                Button(
                                    onClick = { pUnit = k },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (pUnit == k) MidBlue else Color.LightGray
                                    ),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text(l, fontSize = 11.sp, color = if (pUnit == k) Color.White else DarkBlue) }
                            }
                        }
                    }
                    if (categories.isNotEmpty()) {
                        item {
                            Text("Kategoriya:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp)
                        }
                        items(categories) { cat ->
                            Row(
                                Modifier.fillMaxWidth().clickable { pCategoryId = cat.id },
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = pCategoryId == cat.id,
                                    onClick = { pCategoryId = cat.id }
                                )
                                Text(cat.name, fontSize = 13.sp, color = DarkBlue)
                            }
                        }
                    }
                    if (errorMsg.isNotEmpty()) item { Text(errorMsg, color = RedBusy, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            pName.isEmpty() -> { errorMsg = "Nom kiriting"; return@Button }
                            pPrice.toDoubleOrNull() == null -> { errorMsg = "Narxni to'g'ri kiriting"; return@Button }
                            pCategoryId == null -> { errorMsg = "Kategoriya tanlang"; return@Button }
                        }
                        scope.launch {
                            ApiService.createProduct(token, pName, pPrice.toDouble(), pUnit, pCategoryId!!)
                                .onSuccess {
                                    showAddProduct = false; pName = ""; pPrice = ""; errorMsg = ""
                                    refresh()
                                }.onFailure { errorMsg = "Xato: ${it.message}" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { showAddProduct = false; errorMsg = "" }) { Text("Bekor") } }
        )
    }

    // ── TAHRIRLASH ──
    editProduct?.let { prod ->
        var eName by remember(prod) { mutableStateOf(prod.name) }
        var ePrice by remember(prod) { mutableStateOf(prod.price.toLong().toString()) }
        var eUnit by remember(prod) { mutableStateOf(prod.unit) }
        AlertDialog(
            onDismissRequest = { editProduct = null },
            title = { Text("Mahsulotni tahrirlash", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = eName, onValueChange = { eName = it },
                        label = { Text("Nomi") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = ePrice, onValueChange = { ePrice = it.filter { c -> c.isDigit() } },
                        label = { Text("Narxi (so'm)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                    Text("O'lchov:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("piece" to "Dona", "kg" to "Kg", "liter" to "Litr", "portion" to "Porsiya").forEach { (k, l) ->
                            Button(
                                onClick = { eUnit = k },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (eUnit == k) MidBlue else Color.LightGray
                                ),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) { Text(l, fontSize = 11.sp, color = if (eUnit == k) Color.White else DarkBlue) }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ApiService.updateProduct(
                                token, prod.id, eName,
                                ePrice.toDoubleOrNull() ?: prod.price, eUnit
                            ).onSuccess { editProduct = null; refresh() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { editProduct = null }) { Text("Bekor") } }
        )
    }

    // ── O'CHIRISH TASDIQI ──
    deleteProduct?.let { prod ->
        AlertDialog(
            onDismissRequest = { deleteProduct = null },
            title = { Text("O'chirish", fontWeight = FontWeight.Bold, color = RedBusy) },
            text = { Text("\"${prod.name}\" mahsulotini o'chirishni xohlaysizmi?", color = DarkBlue) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ApiService.deleteProduct(token, prod.id)
                            deleteProduct = null
                            refresh()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedBusy)
                ) { Text("O'chirish") }
            },
            dismissButton = { OutlinedButton(onClick = { deleteProduct = null }) { Text("Bekor") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray)) {
        // ── HEADER (ixcham) ──
        Row(
            Modifier.fillMaxWidth().background(Color.White).padding(horizontal = 16.dp, vertical = 12.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Column {
                Text("Menyu", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
                Text("${products.size} mahsulot · ${categories.size} kategoriya", color = TextGray, fontSize = 11.sp)
            }
            if (isAdmin) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedButton(
                        onClick = { showAddCategory = true },
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Folder, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Kategoriya", fontSize = 11.sp)
                    }
                    Button(
                        onClick = { showAddProduct = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Icon(Icons.Default.Add, null, modifier = Modifier.size(14.dp))
                        Spacer(Modifier.width(2.dp))
                        Text("Taom", fontSize = 11.sp)
                    }
                }
            }
        }

        // ── KATEGORIYALAR FILTER (horizontal scroll, ixcham) ──
        if (categories.isNotEmpty()) {
            LazyRow(
                modifier = Modifier.background(Color.White).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    FilterChip(
                        selected = selectedCategoryId == null,
                        onClick = { selectedCategoryId = null },
                        label = { Text("Barchasi (${products.size})", fontSize = 12.sp) }
                    )
                }
                items(categories) { cat ->
                    val count = products.count { it.category_id == cat.id }
                    FilterChip(
                        selected = selectedCategoryId == cat.id,
                        onClick = { selectedCategoryId = cat.id },
                        label = { Text("${cat.name} ($count)", fontSize = 12.sp) }
                    )
                }
            }
            Divider(thickness = 1.dp, color = Color(0xFFE5E7EB))
        }

        // ── MAHSULOTLAR RO'YXATI (ixcham) ──
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MidBlue)
            }
            filteredProducts.isEmpty() -> Box(
                Modifier.fillMaxSize().padding(16.dp), Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.RestaurantMenu, null, tint = TextGray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Mahsulot yo'q", color = TextGray)
                    if (isAdmin) Text("Yangi mahsulot qo'shing", color = TextGray, fontSize = 12.sp)
                }
            }
            else -> LazyColumn(
                modifier = Modifier.padding(horizontal = 8.dp).padding(top = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Kategoriya bo'yicha gruppalash
                val grouped = filteredProducts.groupBy { it.category_id }
                val cats = if (selectedCategoryId != null)
                    categories.filter { it.id == selectedCategoryId }
                else categories.filter { grouped.containsKey(it.id) }

                cats.forEach { cat ->
                    val catProducts = grouped[cat.id] ?: emptyList()
                    if (catProducts.isNotEmpty()) {
                        item {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    Modifier.size(4.dp).clip(CircleShape).background(MidBlue)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    cat.name.uppercase(),
                                    fontWeight = FontWeight.Bold, color = DarkBlue,
                                    fontSize = 12.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Text("${catProducts.size}", color = TextGray, fontSize = 11.sp)
                            }
                        }
                        items(catProducts, key = { "catProducts_${it.id}" }) { p ->
                            CompactProductRow(
                                product = p,
                                isAdmin = isAdmin,
                                onEdit = { editProduct = p },
                                onDelete = { deleteProduct = p }
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

// ══════════════════════════════════════
// IXCHAM MAHSULOT QATORI
// ══════════════════════════════════════
@Composable
fun CompactProductRow(
    product: Product,
    isAdmin: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val unitText = when (product.unit) {
        "piece" -> "dona"; "kg" -> "kg"; "gram" -> "gr"
        "liter" -> "l"; "ml" -> "ml"; "portion" -> "porsiya"; else -> product.unit
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
        elevation = CardDefaults.cardElevation(1.dp)
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            Arrangement.SpaceBetween, Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(product.name, fontWeight = FontWeight.Medium, color = DarkBlue, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("%,.0f so'm".format(product.price), color = MidBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    Text("·", color = TextGray, fontSize = 12.sp)
                    Text(unitText, color = TextGray, fontSize = 12.sp)
                }
            }
            if (isAdmin) {
                Row {
                    IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Edit, null, tint = MidBlue, modifier = Modifier.size(16.dp))
                    }
                    IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Delete, null, tint = RedBusy, modifier = Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════
// OMBOR (INVENTORY)
// ══════════════════════════════════════════════
@Composable
fun InventoryScreen(token: String, userRole: String = "") {
    var items by remember { mutableStateOf<List<InventoryItem>>(emptyList()) }
    var products by remember { mutableStateOf<List<Product>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var selectedProduct by remember { mutableStateOf<Product?>(null) }
    var iQuantity by remember { mutableStateOf("") }
    var iUnitCost by remember { mutableStateOf("") }
    var iSupplier by remember { mutableStateOf("") }
    var editItem by remember { mutableStateOf<InventoryItem?>(null) }
    var errorMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()
    val isAdmin = userRole == "admin" || userRole == "super_admin"

    fun refresh() {
        scope.launch {
            ApiService.getInventory(token).onSuccess { items = it }
        }
    }

    LaunchedEffect(Unit) {
        ApiService.getInventory(token).onSuccess { items = it }
        ApiService.getProducts(token).onSuccess { products = it }
        loading = false
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false; iQuantity = ""; iUnitCost = ""
                iSupplier = ""; selectedProduct = null; errorMsg = ""
            },
            title = { Text("Omborga kirim", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Text("Mahsulot tanlang:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp) }
                    items(products) { prod ->
                        Row(
                            Modifier.fillMaxWidth().clickable { selectedProduct = prod },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedProduct?.id == prod.id,
                                onClick = { selectedProduct = prod }
                            )
                            Text(prod.name, fontSize = 13.sp, color = DarkBlue)
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = iQuantity, onValueChange = { iQuantity = it },
                            label = { Text("Miqdor") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal)
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = iUnitCost, onValueChange = { iUnitCost = it.filter { c -> c.isDigit() } },
                            label = { Text("Birlik narxi (so'm)") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = iSupplier, onValueChange = { iSupplier = it },
                            label = { Text("Yetkazuvchi (ixtiyoriy)") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    if (errorMsg.isNotEmpty()) {
                        item { Text(errorMsg, color = RedBusy, fontSize = 13.sp) }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            selectedProduct == null -> { errorMsg = "Mahsulot tanlang"; return@Button }
                            iQuantity.toDoubleOrNull() == null -> { errorMsg = "Miqdorni to'g'ri kiriting"; return@Button }
                        }
                        scope.launch {
                            ApiService.addInventory(
                                token, selectedProduct!!.id,
                                iQuantity.toDouble(),
                                iUnitCost.toDoubleOrNull() ?: 0.0,
                                iSupplier
                            ).onSuccess {
                                showAddDialog = false; iQuantity = ""; iUnitCost = ""
                                iSupplier = ""; selectedProduct = null; errorMsg = ""
                                refresh()
                            }.onFailure { errorMsg = "Xato: ${it.message}" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Kirim qilish") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showAddDialog = false; errorMsg = ""
                    selectedProduct = null; iQuantity = ""
                }) { Text("Bekor") }
            }
        )
    }

    editItem?.let { inv ->
        var eQty by remember(inv) { mutableStateOf(inv.quantity.toString()) }
        var eMinQty by remember(inv) { mutableStateOf(inv.min_quantity.toString()) }
        AlertDialog(
            onDismissRequest = { editItem = null },
            title = { Text("Omborni tahrirlash", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(inv.product_name, fontWeight = FontWeight.Bold, color = DarkBlue)
                    OutlinedTextField(
                        value = eQty, onValueChange = { eQty = it },
                        label = { Text("Joriy miqdor") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = eMinQty, onValueChange = { eMinQty = it },
                        label = { Text("Minimal miqdor") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ApiService.updateInventory(
                                token, inv.id,
                                eQty.toDoubleOrNull() ?: inv.quantity,
                                eMinQty.toDoubleOrNull() ?: inv.min_quantity
                            ).onSuccess { editItem = null; refresh() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { editItem = null }) { Text("Bekor") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Ombor", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
            if (isAdmin) {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Button(
                        onClick = { showAddDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                    ) { Text("+ Kirim", fontSize = 12.sp) }
                    IconButton(onClick = { refresh() }) { Icon(Icons.Default.Refresh, null, tint = MidBlue) }
                }
            }
        }

        val emptyCount = items.count { it.status == "empty" }
        val lowCount = items.count { it.status == "low" }
        if (emptyCount > 0 || lowCount > 0) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (emptyCount > 0) {
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(RedBusy.copy(alpha = 0.1f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) { Text("⚠️ Tugagan: $emptyCount", color = RedBusy, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
                if (lowCount > 0) {
                    Box(
                        Modifier.clip(RoundedCornerShape(8.dp)).background(Accent.copy(alpha = 0.1f))
                            .padding(horizontal = 10.dp, vertical = 5.dp)
                    ) { Text("⚠️ Kam: $lowCount", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
                }
            }
        }

        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MidBlue)
            }
            items.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Default.Inventory2, null, tint = TextGray, modifier = Modifier.size(48.dp))
                    Spacer(Modifier.height(8.dp))
                    Text("Ombor bo'sh", color = TextGray)
                }
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(items, key = { "items_${it.id}" }) { item ->
                    val sc = when (item.status) { "ok" -> GreenOk; "low" -> Accent; else -> RedBusy }
                    val st = when (item.status) { "ok" -> "Yetarli"; "low" -> "Kam"; else -> "Tugagan" }
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(1.dp)
                    ) {
                        Row(
                            Modifier.padding(14.dp).fillMaxWidth(),
                            Arrangement.SpaceBetween, Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(item.product_name, fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 14.sp)
                                Text("Miqdor: ${item.quantity}", color = TextGray, fontSize = 12.sp)
                                Text("Min: ${item.min_quantity}", color = TextGray, fontSize = 11.sp)
                                if (item.supplier.isNotEmpty()) {
                                    Text("Yetkazuvchi: ${item.supplier}", color = TextGray, fontSize = 11.sp)
                                }
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp)).background(sc.copy(alpha = 0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) { Text(st, color = sc, fontWeight = FontWeight.Bold, fontSize = 12.sp) }
                                if (isAdmin) {
                                    IconButton(
                                        onClick = { editItem = item },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.Edit, null, tint = MidBlue, modifier = Modifier.size(16.dp)) }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ══════════════════════════════════════════════
// SOZLAMALAR - bot ulanish fix bilan
// ══════════════════════════════════════════════
@Composable
fun SettingsScreen(token: String = "", userRole: String = "") {
    var showChangePassword by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var showBotDialog by remember { mutableStateOf(false) }
    var showBotDeleteConfirm by remember { mutableStateOf(false) }
    var botToken by remember { mutableStateOf("") }
    var welcomeMessage by remember { mutableStateOf("Xush kelibsiz!") }
    var message by remember { mutableStateOf("") }

    // ⚡ Bot statusi har safar tab ochilganda qayta tekshiriladi
    var botConfigured by remember { mutableStateOf(false) }
    var botLoading by remember { mutableStateOf(true) }
    var refreshKey by remember { mutableStateOf(0) }

    val scope = rememberCoroutineScope()
    val isAdmin = userRole == "admin"

    // Bot config yuklash - har safar refreshKey o'zgarganda
    LaunchedEffect(refreshKey) {
        if (isAdmin) {
            botLoading = true
            ApiService.getBotConfig(token).onSuccess { config ->
                botConfigured = config.configured
                if (config.configured) {
                    botToken = config.bot_token
                    welcomeMessage = config.welcome_message
                }
            }.onFailure {
                botConfigured = false
            }
            botLoading = false
        }
    }

    // ── PAROL O'ZGARTIRISH ──
    if (showChangePassword) {
        AlertDialog(
            onDismissRequest = { showChangePassword = false; message = "" },
            title = { Text("Parolni o'zgartirish", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = oldPassword, onValueChange = { oldPassword = it },
                        label = { Text("Eski parol") },
                        leadingIcon = { Icon(Icons.Default.Lock, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = newPassword, onValueChange = { newPassword = it },
                        label = { Text("Yangi parol") },
                        leadingIcon = { Icon(Icons.Default.LockReset, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = confirmPassword, onValueChange = { confirmPassword = it },
                        label = { Text("Yangi parolni tasdiqlang") },
                        leadingIcon = { Icon(Icons.Default.Verified, null) },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    if (message.isNotEmpty())
                        Text(
                            message,
                            color = if (message.contains("muvaffaq")) GreenOk else RedBusy,
                            fontSize = 13.sp
                        )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            oldPassword.isEmpty() -> { message = "Eski parolni kiriting"; return@Button }
                            newPassword.length < 4 -> { message = "Yangi parol kamida 4 belgi"; return@Button }
                            newPassword != confirmPassword -> { message = "Yangi parollar mos emas!"; return@Button }
                        }
                        scope.launch {
                            ApiService.changePassword(token, oldPassword, newPassword)
                                .onSuccess {
                                    message = "Parol muvaffaqiyatli o'zgartirildi!"
                                    oldPassword = ""; newPassword = ""; confirmPassword = ""
                                }
                                .onFailure { message = "Xato: eski parol noto'g'ri!" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showChangePassword = false; message = "" }) { Text("Bekor") }
            }
        )
    }

    // ── BOT TASDIQI O'CHIRISH ──
    if (showBotDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showBotDeleteConfirm = false },
            title = { Text("Botni o'chirish", fontWeight = FontWeight.Bold, color = RedBusy) },
            text = { Text("Bot sozlamalarini o'chirishni xohlaysizmi?", color = DarkBlue) },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ApiService.deleteBotConfig(token).onSuccess {
                                botConfigured = false
                                botToken = ""
                                showBotDeleteConfirm = false
                                refreshKey++ // qayta yuklash
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = RedBusy)
                ) { Text("O'chirish") }
            },
            dismissButton = { OutlinedButton(onClick = { showBotDeleteConfirm = false }) { Text("Bekor") } }
        )
    }

    // ── TELEGRAM BOT SOZLASH ──
    if (showBotDialog && isAdmin) {
        AlertDialog(
            onDismissRequest = { showBotDialog = false; message = "" },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Icon(Icons.Default.SmartToy, null, tint = MidBlue)
                    Text("Telegram Bot", fontWeight = FontWeight.Bold, color = DarkBlue)
                }
            },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    item {
                        Text(
                            "1. @BotFather orqali yangi bot yarating\n2. Token oling va quyida kiriting",
                            color = TextGray, fontSize = 12.sp
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = botToken, onValueChange = { botToken = it },
                            label = { Text("Bot Token") },
                            placeholder = { Text("1234567890:AAF...") },
                            leadingIcon = { Icon(Icons.Default.VpnKey, null) },
                            modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = welcomeMessage, onValueChange = { welcomeMessage = it },
                            label = { Text("Xush kelibsiz xabari") },
                            leadingIcon = { Icon(Icons.Default.Message, null) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2
                        )
                    }
                    item {
                        Text(
                            "💡 Bot tokenini @BotFather dan oling.",
                            color = TextGray, fontSize = 11.sp
                        )
                    }
                    if (message.isNotEmpty()) {
                        item {
                            Text(
                                message,
                                color = if (message.contains("muvaffaq") || message.contains("✅")) GreenOk else RedBusy,
                                fontSize = 13.sp
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (botToken.isEmpty()) { message = "Bot tokenini kiriting"; return@Button }
                        scope.launch {
                            message = "Saqlanmoqda..."
                            ApiService.saveBotToken(token, botToken, welcomeMessage)
                                .onSuccess {
                                    // ⚡ Backend dan qayta tekshirish
                                    ApiService.getBotConfig(token).onSuccess { cfg ->
                                        botConfigured = cfg.configured
                                        if (cfg.configured) {
                                            message = "✅ Bot saqlandi va ulandi!"
                                            showBotDialog = false
                                            refreshKey++ // qayta yuklash
                                        } else {
                                            message = "❌ Bot saqlandi lekin ulanmadi"
                                        }
                                    }.onFailure {
                                        message = "❌ Tekshirishda xato"
                                    }
                                }
                                .onFailure { message = "❌ Xato: ${it.message}" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = {
                OutlinedButton(onClick = { showBotDialog = false; message = "" }) { Text("Bekor") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Sozlamalar", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
            // Yangilash tugmasi
            IconButton(onClick = { refreshKey++ }) {
                Icon(Icons.Default.Refresh, null, tint = MidBlue)
            }
        }
        Spacer(Modifier.height(16.dp))

        // ── PAROL ──
        Card(
            modifier = Modifier.fillMaxWidth().clickable { showChangePassword = true },
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Row(Modifier.padding(16.dp).fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Box(
                        Modifier.size(40.dp).clip(RoundedCornerShape(10.dp)).background(MidBlue.copy(0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Lock, null, tint = MidBlue)
                    }
                    Column {
                        Text("Parolni o'zgartirish", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 14.sp)
                        Text("Kirish ma'lumotlarini yangilash", color = TextGray, fontSize = 12.sp)
                    }
                }
                Icon(Icons.Default.ChevronRight, null, tint = TextGray)
            }
        }

        // ── BOT (faqat admin) ──
        if (isAdmin) {
            Spacer(Modifier.height(8.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (botConfigured) Color(0xFFF0FDF4) else Color.White
                )
            ) {
                Column {
                    Row(
                        Modifier.padding(16.dp).fillMaxWidth().clickable { showBotDialog = true },
                        Arrangement.SpaceBetween, Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                Modifier.size(40.dp).clip(RoundedCornerShape(10.dp))
                                    .background(if (botConfigured) GreenOk.copy(0.15f) else MidBlue.copy(0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    Icons.Default.SmartToy, null,
                                    tint = if (botConfigured) GreenOk else MidBlue
                                )
                            }
                            Column {
                                Text("Telegram Bot", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 14.sp)
                                if (botLoading) {
                                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        CircularProgressIndicator(modifier = Modifier.size(10.dp), strokeWidth = 1.5.dp, color = TextGray)
                                        Text("Tekshirilmoqda...", color = TextGray, fontSize = 12.sp)
                                    }
                                } else {
                                    Text(
                                        if (botConfigured) "✅ Bot ulangan" else "Bot ulanmagan",
                                        color = if (botConfigured) GreenOk else TextGray,
                                        fontSize = 12.sp,
                                        fontWeight = if (botConfigured) FontWeight.Bold else FontWeight.Normal
                                    )
                                }
                            }
                        }
                        Icon(
                            if (botConfigured) Icons.Default.Edit else Icons.Default.ChevronRight,
                            null, tint = MidBlue
                        )
                    }
                    if (botConfigured) {
                        Divider()
                        Row(
                            Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().clickable { showBotDeleteConfirm = true },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(Icons.Default.Delete, null, tint = RedBusy, modifier = Modifier.size(18.dp))
                            Text("Botni o'chirish", color = RedBusy, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

// ══════════════════════════════════════════════
// TENANTLAR (SUPER ADMIN)
// ══════════════════════════════════════════════
@Composable
fun TenantsScreen(token: String) {
    var superStats by remember { mutableStateOf<SuperStats?>(null) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editTenant by remember { mutableStateOf<TenantStats?>(null) }
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("restoran") }
    var phone by remember { mutableStateOf("") }
    var adminName by remember { mutableStateOf("") }
    var adminLogin by remember { mutableStateOf("") }
    var adminPassword by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            ApiService.getSuperStats(token).onSuccess { superStats = it }
            loading = false
        }
    }

    LaunchedEffect(Unit) { refresh() }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("Yangi Biznes", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        OutlinedTextField(
                            value = name, onValueChange = { name = it },
                            label = { Text("Biznes nomi") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    item {
                        Text("Turi:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp)
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf("restoran", "choyxona", "kafe").forEach { t ->
                                Button(
                                    onClick = { type = t },
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (type == t) MidBlue else Color.LightGray
                                    ),
                                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                ) { Text(t, fontSize = 11.sp, color = if (type == t) Color.White else DarkBlue) }
                            }
                        }
                    }
                    item {
                        OutlinedTextField(
                            value = phone, onValueChange = { phone = it },
                            label = { Text("Telefon") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    item { Text("Admin ma'lumotlari:", fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 13.sp) }
                    item {
                        OutlinedTextField(
                            value = adminName, onValueChange = { adminName = it },
                            label = { Text("Admin ismi") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = adminLogin, onValueChange = { adminLogin = it },
                            label = { Text("Admin login") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    item {
                        OutlinedTextField(
                            value = adminPassword, onValueChange = { adminPassword = it },
                            label = { Text("Admin parol") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                        )
                    }
                    if (errorMsg.isNotEmpty()) item { Text(errorMsg, color = RedBusy, fontSize = 13.sp) }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            name.isEmpty() -> { errorMsg = "Biznes nomini kiriting"; return@Button }
                            adminLogin.isEmpty() -> { errorMsg = "Admin loginini kiriting"; return@Button }
                            adminPassword.length < 4 -> { errorMsg = "Parol kamida 4 belgi"; return@Button }
                        }
                        scope.launch {
                            ApiService.createTenant(
                                token, name, type, "", phone, adminName, adminLogin, adminPassword
                            ).onSuccess {
                                showAddDialog = false
                                name = ""; phone = ""; adminName = ""; adminLogin = ""; adminPassword = ""; errorMsg = ""
                                refresh()
                            }.onFailure { errorMsg = "Xato: ${it.message}" }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { showAddDialog = false; errorMsg = "" }) { Text("Bekor") } }
        )
    }

    editTenant?.let { et ->
        var eName by remember(et) { mutableStateOf(et.name) }
        var eType by remember(et) { mutableStateOf(et.type ?: "restoran") }
        var ePhone by remember(et) { mutableStateOf(et.phone ?: "") }
        AlertDialog(
            onDismissRequest = { editTenant = null },
            title = { Text("Biznesni tahrirlash", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = eName, onValueChange = { eName = it },
                        label = { Text("Biznes nomi") }, modifier = Modifier.fillMaxWidth()
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        listOf("restoran", "choyxona", "kafe").forEach { t ->
                            Button(
                                onClick = { eType = t },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (eType == t) MidBlue else Color.LightGray
                                ),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) { Text(t, fontSize = 11.sp, color = if (eType == t) Color.White else DarkBlue) }
                        }
                    }
                    OutlinedTextField(
                        value = ePhone, onValueChange = { ePhone = it },
                        label = { Text("Telefon") }, modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ApiService.updateTenant(token, et.id, eName, eType, ePhone)
                                .onSuccess { editTenant = null; refresh() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { editTenant = null }) { Text("Bekor") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Bizneslar", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("+ Qo'shish", fontSize = 12.sp) }
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, null, tint = MidBlue)
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        superStats?.let { s ->
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = MidBlue)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Jami", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        Text("${s.total_tenants} ta", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
                Card(modifier = Modifier.weight(1f), colors = CardDefaults.cardColors(containerColor = GreenOk)) {
                    Column(Modifier.padding(12.dp)) {
                        Text("Faol", color = Color.White.copy(alpha = 0.8f), fontSize = 12.sp)
                        Text("${s.active_tenants} ta", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MidBlue)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(superStats?.tenants ?: emptyList()) { tenant ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Column(Modifier.padding(16.dp)) {
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Column {
                                    Text(tenant.name, fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 15.sp)
                                    Text(tenant.type ?: "Restoran", color = TextGray, fontSize = 12.sp)
                                }
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(if (tenant.is_active) GreenOk.copy(0.15f) else RedBusy.copy(0.15f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        if (tenant.is_active) "Faol" else "Bloklangan",
                                        color = if (tenant.is_active) GreenOk else RedBusy,
                                        fontSize = 12.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween) {
                                Column {
                                    Text("Tushum", color = TextGray, fontSize = 11.sp)
                                    Text("%,.0f so'm".format(tenant.total_revenue), color = MidBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Column {
                                    Text("Buyurtmalar", color = TextGray, fontSize = 11.sp)
                                    Text("${tenant.total_orders} ta", color = DarkBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                                Column {
                                    Text("Xodimlar", color = TextGray, fontSize = 11.sp)
                                    Text("${tenant.users_count} ta", color = DarkBlue, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                Button(
                                    onClick = {
                                        scope.launch {
                                            ApiService.toggleTenant(token, tenant.id)
                                            refresh()
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (tenant.is_active) RedBusy else GreenOk
                                    ),
                                    contentPadding = PaddingValues(vertical = 6.dp)
                                ) { Text(if (tenant.is_active) "Bloklash" else "Faollashtirish", fontSize = 11.sp) }
                                IconButton(onClick = { editTenant = tenant }) {
                                    Icon(Icons.Default.Edit, null, tint = MidBlue)
                                }
                                IconButton(onClick = {
                                    scope.launch {
                                        ApiService.deleteTenant(token, tenant.id); refresh()
                                    }
                                }) {
                                    Icon(Icons.Default.Delete, null, tint = RedBusy)
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}

// ══════════════════════════════════════════════
// XODIMLAR (WORKERS)
// ══════════════════════════════════════════════
@Composable
fun WorkersScreen(token: String) {
    var workers by remember { mutableStateOf<List<Worker>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var showAddDialog by remember { mutableStateOf(false) }
    var editWorker by remember { mutableStateOf<Worker?>(null) }
    var fullName by remember { mutableStateOf("") }
    var login by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var selectedRole by remember { mutableStateOf("cashier") }
    var errorMsg by remember { mutableStateOf("") }
    val scope = rememberCoroutineScope()

    fun refresh() {
        scope.launch {
            ApiService.getWorkers(token).onSuccess { workers = it }
        }
    }

    LaunchedEffect(Unit) {
        ApiService.getWorkers(token).onSuccess { workers = it }
        loading = false
    }

    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = {
                showAddDialog = false; fullName = ""; login = ""; password = ""; errorMsg = ""
                selectedRole = "cashier"
            },
            title = { Text("Yangi Xodim", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = fullName, onValueChange = { fullName = it },
                        label = { Text("Ism Familiya") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = login, onValueChange = { login = it },
                        label = { Text("Login") }, modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    OutlinedTextField(
                        value = password, onValueChange = { password = it },
                        label = { Text("Parol") },
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(), singleLine = true
                    )
                    Text("Lavozim:", fontWeight = FontWeight.Bold, color = DarkBlue)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("cashier" to "Kassir", "waiter" to "Ofitsiant").forEach { (k, l) ->
                            Button(
                                onClick = { selectedRole = k },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (selectedRole == k) MidBlue else Color.LightGray
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(l, fontSize = 13.sp, color = if (selectedRole == k) Color.White else DarkBlue)
                            }
                        }
                    }
                    if (errorMsg.isNotEmpty()) Text(errorMsg, color = RedBusy, fontSize = 13.sp)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        when {
                            fullName.isEmpty() -> { errorMsg = "Ism kiriting"; return@Button }
                            login.isEmpty() -> { errorMsg = "Login kiriting"; return@Button }
                            password.length < 4 -> { errorMsg = "Parol kamida 4 belgi"; return@Button }
                        }
                        scope.launch {
                            ApiService.createWorker(token, fullName, login, password, selectedRole)
                                .onSuccess {
                                    showAddDialog = false
                                    fullName = ""; login = ""; password = ""; errorMsg = ""
                                    refresh()
                                }.onFailure {
                                    errorMsg = if (it.message?.contains("band") == true)
                                        "Bu login band!" else "Xato: ${it.message}"
                                }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = {
                OutlinedButton(onClick = {
                    showAddDialog = false; errorMsg = ""
                    fullName = ""; login = ""; password = ""
                }) { Text("Bekor") }
            }
        )
    }

    editWorker?.let { worker ->
        var eName by remember(worker) { mutableStateOf(worker.full_name) }
        var eRole by remember(worker) { mutableStateOf(worker.role) }
        AlertDialog(
            onDismissRequest = { editWorker = null },
            title = { Text("Xodimni tahrirlash", fontWeight = FontWeight.Bold, color = DarkBlue) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = eName, onValueChange = { eName = it },
                        label = { Text("Ism Familiya") }, modifier = Modifier.fillMaxWidth()
                    )
                    Text("Lavozim:", fontWeight = FontWeight.Bold, color = DarkBlue)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("cashier" to "Kassir", "waiter" to "Ofitsiant").forEach { (k, l) ->
                            Button(
                                onClick = { eRole = k },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (eRole == k) MidBlue else Color.LightGray
                                ),
                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                Text(l, fontSize = 13.sp, color = if (eRole == k) Color.White else DarkBlue)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            ApiService.updateWorker(token, worker.id, eName, eRole)
                                .onSuccess { editWorker = null; refresh() }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = GreenOk)
                ) { Text("Saqlash") }
            },
            dismissButton = { OutlinedButton(onClick = { editWorker = null }) { Text("Bekor") } }
        )
    }

    Column(modifier = Modifier.fillMaxSize().background(BgGray).padding(16.dp)) {
        Row(Modifier.fillMaxWidth(), Arrangement.SpaceBetween, Alignment.CenterVertically) {
            Text("Xodimlar", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = DarkBlue)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { showAddDialog = true },
                    colors = ButtonDefaults.buttonColors(containerColor = MidBlue),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                ) { Text("+ Qo'shish", fontSize = 12.sp) }
                IconButton(onClick = { refresh() }) {
                    Icon(Icons.Default.Refresh, null, tint = MidBlue)
                }
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            loading -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                CircularProgressIndicator(color = MidBlue)
            }
            workers.isEmpty() -> Box(Modifier.fillMaxSize(), Alignment.Center) {
                Text("Xodimlar topilmadi", color = TextGray)
            }
            else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(workers) { worker ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Color.White),
                        elevation = CardDefaults.cardElevation(2.dp)
                    ) {
                        Row(
                            Modifier.padding(16.dp).fillMaxWidth(),
                            Arrangement.SpaceBetween, Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(worker.full_name, fontWeight = FontWeight.Bold, color = DarkBlue, fontSize = 15.sp)
                                Text(
                                    when (worker.role) { "cashier" -> "💳 Kassir"; "waiter" -> "🍽 Ofitsiant"; else -> worker.role },
                                    color = TextGray, fontSize = 12.sp
                                )
                                Text("@${worker.login}", color = TextGray, fontSize = 11.sp)
                            }
                            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Box(
                                    Modifier.clip(RoundedCornerShape(8.dp))
                                        .background(if (worker.is_active) GreenOk.copy(0.15f) else RedBusy.copy(0.15f))
                                        .padding(horizontal = 8.dp, vertical = 3.dp)
                                ) {
                                    Text(
                                        if (worker.is_active) "Faol" else "Bloklangan",
                                        color = if (worker.is_active) GreenOk else RedBusy,
                                        fontSize = 11.sp, fontWeight = FontWeight.Bold
                                    )
                                }
                                Row {
                                    IconButton(
                                        onClick = { editWorker = worker },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.Edit, null, tint = MidBlue, modifier = Modifier.size(16.dp)) }
                                    IconButton(
                                        onClick = {
                                            scope.launch {
                                                ApiService.deleteWorker(token, worker.id); refresh()
                                            }
                                        },
                                        modifier = Modifier.size(32.dp)
                                    ) { Icon(Icons.Default.Delete, null, tint = RedBusy, modifier = Modifier.size(16.dp)) }
                                }
                            }
                        }
                    }
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }
}
