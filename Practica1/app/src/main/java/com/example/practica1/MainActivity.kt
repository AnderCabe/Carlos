package com.example.practica1

import android.app.DatePickerDialog
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.AtomicFile
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.practica1.ui.theme.Practica1Theme
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileNotFoundException
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors

private val worker = Executors.newSingleThreadExecutor()
private val mainHandler = Handler(Looper.getMainLooper())
private val blue = Color(0xFF123DB2)
private val red = Color(0xFFBD1826)
private val green = Color(0xFF16802D)
private val magenta = Color(0xFFAF009D)
private val zero = BigDecimal.ZERO
private val locale = Locale.forLanguageTag("es-ES")
private val dateFormat = DateTimeFormatter.ofPattern("dd/MM/yyyy")
private val monthFormat = DateTimeFormatter.ofPattern("MMMM yyyy", locale)
private fun JSONObject.s(key: String) = optString(key, "")
private fun JSONObject.n(key: String) = s(key).toBigDecimalOrNull() ?: zero
private fun clone(row: JSONObject) = JSONObject(row.toString())
private fun decimal(value: String) = value.trim().replace(',', '.').toBigDecimal()
private fun round(n: BigDecimal) = n.setScale(2, RoundingMode.HALF_UP)
private fun euro(n: BigDecimal) = NumberFormat.getCurrencyInstance(locale).format(n)
private fun percent(n: BigDecimal, rate: BigDecimal) = round(n * rate / BigDecimal("100"))
private fun prettyDate(value: String) = runCatching { LocalDate.parse(value).format(dateFormat) }.getOrDefault("Sin fecha")
private fun prettyMonth(value: String) = runCatching { YearMonth.parse(value).format(monthFormat) }.getOrDefault("Seleccionar mes")
private fun rows(db: JSONObject, kind: String): List<JSONObject> {
    val a = db.getJSONArray(kind)
    return (0 until a.length()).map { a.getJSONObject(it) }
}
private fun total(list: List<JSONObject>, key: String) = list.fold(zero) { a, b -> a + b.n(key) }
private fun emptyDatabase() = JSONObject().put("version", 1).put("company", JSONObject())
    .put("employees", JSONArray()).put("payrolls", JSONArray()).put("sales", JSONArray()).put("expenses", JSONArray())

private class Store(directory: File) {
    private val file = AtomicFile(File(directory, "gestion_empresa.json"))
    var db by mutableStateOf(emptyDatabase()); private set
    var loading by mutableStateOf(true); private set
    var saving by mutableStateOf(false); private set
    var fatal by mutableStateOf(""); private set
    var error by mutableStateOf(""); private set
    init { load() }
    fun clearError() { error = "" }
    fun load() {
        loading = true; fatal = ""
        worker.execute {
            try {
                val result = try { file.openRead().use { JSONObject(it.readBytes().toString(Charsets.UTF_8)) } }
                catch (e: FileNotFoundException) { if (file.baseFile.exists()) throw e else emptyDatabase() }
                require(result.getInt("version") == 1) { "Versión de datos no compatible" }
                result.getJSONObject("company")
                listOf("employees", "payrolls", "sales", "expenses").forEach { k -> rows(result, k).forEach { require(it.s("id").isNotBlank()) } }
                mainHandler.post { db = result; loading = false }
            } catch (e: Exception) {
                mainHandler.post { fatal = "No se pueden leer los datos. El fichero no se sobrescribirá. ${e.message}"; loading = false }
            }
        }
    }
    private fun save(next: JSONObject, done: () -> Unit) {
        if (loading || saving || fatal.isNotEmpty()) return
        saving = true; error = ""
        worker.execute {
            var stream: java.io.FileOutputStream? = null
            try {
                stream = file.startWrite()
                stream.write(next.toString(2).toByteArray(Charsets.UTF_8))
                file.finishWrite(stream)
                stream = null
                mainHandler.post { db = next; saving = false; done() }
            } catch (e: Exception) {
                stream?.let { file.failWrite(it) }
                mainHandler.post { saving = false; error = "No se ha guardado: ${e.message}" }
            }
        }
    }
    fun put(kind: String, row: JSONObject, done: () -> Unit = {}) {
        val next = clone(db)
        if (kind == "company") next.put(kind, row)
        else {
            val list = rows(next, kind).toMutableList()
            val at = list.indexOfFirst { it.s("id") == row.s("id") }
            if (at < 0) list.add(row) else list[at] = row
            next.put(kind, JSONArray(list))
        }
        save(next, done)
    }
    fun delete(kind: String, id: String, done: () -> Unit) {
        val next = clone(db).put(kind, JSONArray(rows(db, kind).filterNot { it.s("id") == id }))
        save(next, done)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val store = Store(filesDir)
        setContent { Practica1Theme {
            MaterialTheme(colorScheme = lightColorScheme(primary = green, onPrimary = Color.White,
                secondary = magenta, surface = Color.White, background = Color.White,
                onSurface = Color.Black, onBackground = Color.Black)) { App(store) }
        } }
    }
}

private data class Field(val key: String, val label: String, val required: Boolean = false,
                         val numeric: Boolean = false, val initial: String = "", val choices: List<Pair<String, String>> = emptyList())
private data class Edit(val kind: String, val row: JSONObject?)
private data class Removal(val kind: String, val row: JSONObject)
private fun fields(kind: String, db: JSONObject): List<Field> = when (kind) {
    "company" -> listOf(Field("name", "Nombre de empresa", true), Field("taxId", "NIF"), Field("address", "Dirección"), Field("phone", "Teléfono"), Field("email", "Correo electrónico"))
    "employees" -> listOf(
        Field("name", "Nombre", true), Field("surname", "Apellidos", true), Field("document", "DNI / NIE", true),
        Field("phone", "Teléfono"), Field("email", "Correo electrónico"), Field("address", "Dirección"), Field("iban", "IBAN"),
        Field("position", "Puesto", true), Field("department", "Departamento"),
        Field("date", "Fecha de incorporación", true, initial = LocalDate.now().toString()),
        Field("active", "Situación", true, initial = "true", choices = listOf("true" to "Activo", "false" to "Inactivo")),
        Field("gross", "Bruto mensual (€)", true, true, "0"), Field("retRate", "Retención simulada (%)", true, true, "0"),
        Field("dedRate", "Deducción trabajador (%)", true, true, "0"), Field("companyRate", "Aportación empresa (%)", true, true, "0"), Field("notes", "Observaciones"))
    "payrolls" -> listOf(
        Field("employeeId", "Empleado", true, choices = rows(db, "employees").map { it.s("id") to "${it.s("name")} ${it.s("surname")}" }),
        Field("period", "Mes de nómina", true, initial = YearMonth.now().toString()),
        Field("gross", "Sueldo bruto base (€)", true, true, "0"), Field("extra", "Complementos (€)", true, true, "0"),
        Field("retRate", "Retención simulada (%)", true, true, "0"), Field("dedRate", "Deducción trabajador (%)", true, true, "0"),
        Field("companyRate", "Aportación empresa (%)", true, true, "0"),
        Field("paid", "Estado", true, initial = "false", choices = listOf("false" to "Pendiente", "true" to "Pagada")), Field("paidDate", "Fecha de pago"))
    else -> buildList {
        val sale = kind == "sales"
        add(Field("number", if (sale) "Número de factura" else "Referencia", sale))
        add(Field("party", if (sale) "Cliente" else "Proveedor", true)); add(Field("taxId", "NIF"))
        add(Field("date", "Fecha de registro", true, initial = LocalDate.now().toString())); add(Field("concept", "Concepto", true))
        if (!sale) add(Field("category", "Categoría", true, initial = "Otros", choices = listOf("Alquiler", "Suministros", "Material", "Servicios", "Otros").map { it to it }))
        add(Field("base", "Base sin IVA (€)", true, true)); add(Field("vatRate", "IVA (%)", true, true, "0"))
        add(Field("paid", "Estado", true, initial = "false", choices = listOf("false" to "Pendiente", "true" to if (sale) "Cobrada" else "Pagado")))
        add(Field("paidDate", if (sale) "Fecha de cobro" else "Fecha de pago"))
    }
}

private fun buildRow(kind: String, values: Map<String, String>, old: JSONObject?, db: JSONObject): JSONObject {
    val row = JSONObject().put("id", old?.s("id") ?: UUID.randomUUID().toString())
    fields(kind, db).forEach { f ->
        val value = values[f.key].orEmpty().trim()
        require(!f.required || value.isNotEmpty()) { "${f.label}: campo obligatorio" }
        if (f.numeric) {
            val n = runCatching { decimal(value) }.getOrElse { throw IllegalArgumentException("${f.label}: número no válido") }
            require(n >= zero) { "${f.label}: no puede ser negativo" }
            if (f.key.endsWith("Rate")) require(n <= BigDecimal("100")) { "${f.label}: máximo 100" }
            else require(n.scale() <= 2) { "${f.label}: máximo dos decimales" }
            row.put(f.key, n.toPlainString())
        } else row.put(f.key, value)
    }
    listOf("date", "paidDate").forEach { key -> if (row.s(key).isNotEmpty()) {
        require(runCatching { LocalDate.parse(row.s(key)) }.isSuccess) { "Selecciona una fecha válida" }
    } }
    if (kind == "company" || kind == "employees") require(row.s("email").isEmpty() || android.util.Patterns.EMAIL_ADDRESS.matcher(row.s("email")).matches()) { "Correo electrónico no válido" }
    if (kind == "employees") {
        row.put("document", row.s("document").replace(" ", "").uppercase(Locale.ROOT))
        row.put("iban", row.s("iban").replace(" ", "").uppercase(Locale.ROOT))
        require(rows(db, kind).none { it.s("id") != row.s("id") && it.s("document").equals(row.s("document"), true) }) { "DNI/NIE repetido" }
    }
    if (kind == "payrolls") {
        require(runCatching { YearMonth.parse(row.s("period")) }.isSuccess) { "Selecciona el mes de nómina" }
        val employee = rows(db, "employees").find { it.s("id") == row.s("employeeId") } ?: throw IllegalArgumentException("Selecciona un empleado")
        require(old != null || employee.s("active") == "true") { "Selecciona un empleado activo" }
        require(rows(db, kind).none { it.s("id") != row.s("id") && it.s("employeeId") == row.s("employeeId") && it.s("period") == row.s("period") }) { "Ya existe una nómina de ese empleado en ese mes" }
        val sameEmployee = old != null && old.s("employeeId") == row.s("employeeId")
        row.put("employeeName", if (sameEmployee) old!!.s("employeeName") else "${employee.s("name")} ${employee.s("surname")}")
        row.put("employeeDocument", if (sameEmployee) old!!.s("employeeDocument") else employee.s("document"))
        row.put("employeeIban", if (sameEmployee) old!!.s("employeeIban") else employee.s("iban"))
        val gross = round(row.n("gross") + row.n("extra"))
        val ret = percent(gross, row.n("retRate")); val ded = percent(gross, row.n("dedRate")); val contribution = percent(gross, row.n("companyRate"))
        require(ret + ded <= gross) { "Las deducciones superan el bruto" }
        row.put("grossTotal", gross.toPlainString()).put("retention", ret.toPlainString()).put("deduction", ded.toPlainString())
            .put("net", (gross - ret - ded).toPlainString()).put("contribution", contribution.toPlainString()).put("cost", (gross + contribution).toPlainString())
    }
    if (kind == "sales" || kind == "expenses") {
        require(row.n("base") > zero) { "La base debe ser mayor que cero" }
        if (kind == "sales") require(rows(db, kind).none { it.s("id") != row.s("id") && it.s("number").equals(row.s("number"), true) }) { "Número de factura repetido" }
        val vat = percent(row.n("base"), row.n("vatRate"))
        row.put("vat", vat.toPlainString()).put("total", round(row.n("base") + vat).toPlainString())
    }
    if (kind in listOf("sales", "expenses", "payrolls")) {
        require(row.s("paid") != "true" || row.s("paidDate").isNotEmpty()) { "Selecciona una fecha de pago / cobro" }
        if (row.s("paid") != "true") row.put("paidDate", "")
        if (row.s("paidDate").isNotEmpty() && row.s("date").isNotEmpty()) require(!LocalDate.parse(row.s("paidDate")).isBefore(LocalDate.parse(row.s("date")))) { "El pago no puede ser anterior al registro" }
    }
    return row
}

private fun startDate(range: String, today: LocalDate = LocalDate.now()): LocalDate = when (range) {
    "1 semana" -> today.minusDays(6)
    "3 meses" -> today.minusMonths(3).plusDays(1)
    "6 meses" -> today.minusMonths(6).plusDays(1)
    "1 año" -> today.minusYears(1).plusDays(1)
    else -> today.minusMonths(1).plusDays(1)
}
private fun inRange(row: JSONObject, kind: String, range: String): Boolean = runCatching {
    val today = LocalDate.now(); val start = startDate(range, today)
    if (kind == "payrolls") {
        val month = YearMonth.parse(row.s("period"))
        !month.atEndOfMonth().isBefore(start) && !month.atDay(1).isAfter(today)
    } else {
        val date = LocalDate.parse(row.s("date"))
        !date.isBefore(start) && !date.isAfter(today)
    }
}.getOrDefault(false)

@Composable
private fun Title(text: String, color: Color = magenta) {
    Text(text.uppercase(locale), color = color, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
        style = MaterialTheme.typography.titleLarge, modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
}
@Composable
private fun Input(label: String, value: String, enabled: Boolean, numeric: Boolean = false, change: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = change, label = { Text(label) }, enabled = enabled,
        modifier = Modifier.fillMaxWidth(), singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (numeric) KeyboardType.Decimal else KeyboardType.Text))
}
@Composable
private fun RangeButtons(selected: String, change: (String) -> Unit) {
    Text("MOSTRAR ÚLTIMOS", color = blue, fontWeight = FontWeight.Bold)
    listOf("1 semana", "1 mes", "3 meses", "6 meses", "1 año").chunked(3).forEach { group ->
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            group.forEach { option -> Button(onClick = { change(option) }, modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 4.dp), colors = ButtonDefaults.buttonColors(
                    containerColor = if (selected == option) green else Color(0xFFE9E9E9), contentColor = if (selected == option) Color.White else Color.Black)) { Text(option) } }
        }
    }
    Text("${startDate(selected).format(dateFormat)} — ${LocalDate.now().format(dateFormat)}", color = blue, style = MaterialTheme.typography.bodySmall)
}
@Composable
private fun CalendarInput(label: String, value: String, enabled: Boolean, required: Boolean, change: (String) -> Unit) {
    val context = LocalContext.current
    Text(label + if (required) " *" else "", color = blue)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, modifier = Modifier.weight(1f), onClick = {
            val date = runCatching { LocalDate.parse(value) }.getOrDefault(LocalDate.now())
            DatePickerDialog(context, { _, y, m, d -> change(LocalDate.of(y, m + 1, d).toString()) }, date.year, date.monthValue - 1, date.dayOfMonth).show()
        }) { Text(prettyDate(value)) }
        TextButton(enabled = enabled, onClick = { change(LocalDate.now().toString()) }) { Text("Hoy") }
    }
    if (!required && value.isNotEmpty()) TextButton(enabled = enabled, onClick = { change("") }) { Text("Quitar fecha") }
}
@Composable
private fun MonthInput(value: String, enabled: Boolean, change: (String) -> Unit) {
    val month = runCatching { YearMonth.parse(value) }.getOrDefault(YearMonth.now())
    Text("MES DE LA NÓMINA *", color = blue)
    Text(prettyMonth(month.toString()), color = red, fontWeight = FontWeight.Bold)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(enabled = enabled, onClick = { change(month.minusMonths(1).toString()) }, modifier = Modifier.weight(1f)) { Text("Anterior") }
        OutlinedButton(enabled = enabled, onClick = { change(month.plusMonths(1).toString()) }, modifier = Modifier.weight(1f)) { Text("Siguiente") }
    }
    TextButton(enabled = enabled, onClick = { change(YearMonth.now().toString()) }) { Text("Mes actual") }
}
@Composable
private fun Calculations(kind: String, row: JSONObject) {
    val items = if (kind == "payrolls") listOf("grossTotal" to "Bruto total", "retention" to "Retención simulada", "deduction" to "Deducción trabajador", "net" to "Neto", "contribution" to "Aportación empresa", "cost" to "Coste empresa")
    else listOf("base" to "Base sin IVA", "vat" to "Cuota IVA", "total" to "Total")
    items.forEachIndexed { index, (key, label) -> Text("$label: ${euro(row.n(key))}", color = if (index % 2 == 0) red else blue) }
}
@Composable
private fun Summary(kind: String, row: JSONObject) {
    Text(when (kind) {
        "employees" -> "${row.s("name")} ${row.s("surname")}"
        "payrolls" -> "${row.s("employeeName")} · ${prettyMonth(row.s("period"))}"
        else -> "${row.s("party")} · ${row.s("number").ifEmpty { row.s("category") }}"
    }, color = blue, fontWeight = FontWeight.Bold)
    if (kind == "employees") {
        Text("${row.s("position")} · ${if (row.s("active") == "true") "Activo" else "Inactivo"}", color = red)
        Text("Bruto mensual: ${euro(row.n("gross"))}", color = blue)
    } else {
        if (kind != "payrolls") Text("${prettyDate(row.s("date"))} · ${row.s("concept")}", color = red)
        Text(if (kind == "payrolls") "Neto: ${euro(row.n("net"))} · Coste: ${euro(row.n("cost"))}" else "Total: ${euro(row.n("total"))}", color = blue)
        Text(if (row.s("paid") == "true") "${if (kind == "sales") "Cobrada" else "Pagado"}: ${prettyDate(row.s("paidDate"))}" else "Pendiente", color = red)
    }
}

@Composable
private fun Editor(edit: Edit, db: JSONObject, busy: Boolean, cancel: () -> Unit, save: (JSONObject) -> Unit) {
    val fs = remember { fields(edit.kind, db) }
    val initial = remember { fs.associate { it.key to (edit.row?.s(it.key) ?: it.initial) } }
    val values = remember { mutableStateMapOf<String, String>().apply { putAll(initial) } }
    var error by remember { mutableStateOf("") }; var discard by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<JSONObject?>(null) }
    fun leave() { if (!busy) { if (values.toMap() != initial) discard = true else cancel() } }
    BackHandler { leave() }
    fun change(key: String, value: String) {
        values[key] = value; error = ""; preview = null
        if (edit.kind == "payrolls" && key == "employeeId") rows(db, "employees").find { it.s("id") == value }?.let { employee ->
            listOf("gross", "retRate", "dedRate", "companyRate").forEach { values[it] = employee.s(it) }
        }
        if (key == "paid") values["paidDate"] = if (value == "true") LocalDate.now().toString() else ""
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Title(if (edit.row == null) "Nuevo registro" else "Editar registro")
        if (edit.kind == "payrolls") Text("Simulación educativa: porcentajes introducidos manualmente.", color = blue)
        fs.forEach { f ->
            when {
                f.key == "date" || f.key == "paidDate" -> CalendarInput(f.label, values[f.key].orEmpty(), !busy, f.required) { change(f.key, it) }
                f.key == "period" -> MonthInput(values[f.key].orEmpty(), !busy) { change(f.key, it) }
                f.choices.isNotEmpty() -> {
                    var expanded by remember { mutableStateOf(false) }
                    Text(f.label + if (f.required) " *" else "", color = blue)
                    Box {
                        OutlinedButton(enabled = !busy, onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(f.choices.find { it.first == values[f.key] }?.second ?: "Seleccionar…") }
                        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                            f.choices.forEach { (id, label) -> DropdownMenuItem(text = { Text(label) }, onClick = { change(f.key, id); expanded = false }) }
                        }
                    }
                }
                else -> Input(f.label + if (f.required) " *" else "", values[f.key].orEmpty(), !busy, f.numeric) { change(f.key, it) }
            }
        }
        if (error.isNotEmpty()) Text(error, color = red)
        preview?.let { Calculations(edit.kind, it) }
        if (edit.kind in listOf("payrolls", "sales", "expenses")) OutlinedButton(enabled = !busy, onClick = {
            try { preview = buildRow(edit.kind, values.toMap(), edit.row, db); error = "" } catch (e: Exception) { error = e.message ?: "Datos incorrectos" }
        }) { Text("CALCULAR / COMPROBAR") }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Button(enabled = !busy, onClick = {
                try { val row = buildRow(edit.kind, values.toMap(), edit.row, db); error = ""; save(row) } catch (e: Exception) { error = e.message ?: "Datos incorrectos" }
            }) { Text(if (busy) "GUARDANDO…" else "GUARDAR") }
            OutlinedButton(enabled = !busy, onClick = { leave() }) { Text("CANCELAR") }
        }
        Text("* Obligatorio. Las fechas se eligen con el calendario; no es necesario escribirlas.", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(24.dp))
    }
    if (discard) AlertDialog(onDismissRequest = { discard = false }, title = { Text("¿Descartar cambios?") }, text = { Text("Hay cambios sin guardar.") },
        confirmButton = { TextButton(onClick = { discard = false; cancel() }) { Text("Descartar") } }, dismissButton = { TextButton(onClick = { discard = false }) { Text("Seguir editando") } })
}

@Composable
private fun Detail(edit: Edit, back: () -> Unit) {
    BackHandler { back() }
    val row = edit.row ?: return
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = back) { Text("VOLVER") }; Summary(edit.kind, row); HorizontalDivider(color = green)
        fields(edit.kind, emptyDatabase()).forEachIndexed { index, f ->
            if (f.key != "employeeId") {
                val value = row.s(f.key)
                val display = when (f.key) { "date", "paidDate" -> prettyDate(value); "period" -> prettyMonth(value); else -> f.choices.find { it.first == value }?.second ?: value.ifEmpty { "—" } }
                Text("${f.label}: $display", color = if (index % 2 == 0) red else blue)
            }
        }
        if (edit.kind == "payrolls") { Text("DNI/NIE registrado: ${row.s("employeeDocument")}", color = red); Text("IBAN registrado: ${row.s("employeeIban")}", color = blue) }
        if (edit.kind in listOf("payrolls", "sales", "expenses")) Calculations(edit.kind, row)
        Spacer(Modifier.height(24.dp))
    }
}
@Composable
private fun Dashboard(db: JSONObject, range: String) {
    val sales = rows(db, "sales").filter { inRange(it, "sales", range) }
    val expenses = rows(db, "expenses").filter { inRange(it, "expenses", range) }
    val payrolls = rows(db, "payrolls").filter { inRange(it, "payrolls", range) }
    Text("Empleados activos: ${rows(db, "employees").count { it.s("active") == "true" }}", color = blue)
    val metrics = listOf(
        "Ventas sin IVA" to total(sales, "base"), "Gastos sin IVA" to total(expenses, "base"), "Coste de nóminas" to total(payrolls, "cost"),
        "Resultado estimado" to (total(sales, "base") - total(expenses, "base") - total(payrolls, "cost")),
        "IVA ventas" to total(sales, "vat"), "IVA gastos" to total(expenses, "vat"), "Diferencia IVA (simulación)" to (total(sales, "vat") - total(expenses, "vat")),
        "Bruto de nóminas" to total(payrolls, "grossTotal"), "Retenciones simuladas" to total(payrolls, "retention"), "Deducciones trabajador" to total(payrolls, "deduction"),
        "Aportaciones empresa" to total(payrolls, "contribution"), "Neto de nóminas" to total(payrolls, "net"),
        "Ventas pendientes (con IVA)" to total(sales.filter { it.s("paid") != "true" }, "total"),
        "Gastos pendientes (con IVA)" to total(expenses.filter { it.s("paid") != "true" }, "total"),
        "Nóminas pendientes (neto)" to total(payrolls.filter { it.s("paid") != "true" }, "net"))
    metrics.forEachIndexed { index, (label, amount) ->
        Text(label, color = if (index % 2 == 0) blue else red)
        Text(euro(amount), color = if (index % 2 == 0) red else blue, style = MaterialTheme.typography.titleLarge)
        HorizontalDivider(color = green)
    }
    Text("Intervalos contados hacia atrás desde hoy. Ventas y gastos se filtran por fecha de registro, no de pago.")
    Text("Nóminas: se incluyen completas cuando su mes coincide con algún día del intervalo. No se prorratean; una semana no representa un coste salarial semanal.")
    Text("El resultado no es un saldo bancario. El IVA es una comparación simplificada que no evalúa deducibilidad. Nóminas e impuestos son simulaciones educativas, no cálculos oficiales.")
}

@Composable
private fun App(store: Store) {
    var tab by rememberSaveable { mutableStateOf(0) }; var expense by rememberSaveable { mutableStateOf(false) }
    var range by rememberSaveable { mutableStateOf("1 mes") }; var search by rememberSaveable { mutableStateOf("") }
    var filter by rememberSaveable { mutableStateOf("Todos") }
    var edit by remember { mutableStateOf<Edit?>(null) }; var detail by remember { mutableStateOf<Edit?>(null) }
    var removal by remember { mutableStateOf<Removal?>(null) }; var notice by remember { mutableStateOf("") }
    val db = store.db
    val tabs = listOf("Inicio", "Empleados", "Nóminas", "Facturas", "Empresa")
    val kind = when (tab) { 1 -> "employees"; 2 -> "payrolls"; 3 -> if (expense) "expenses" else "sales"; else -> "company" }
    Scaffold(containerColor = Color.White, bottomBar = {
        if (edit == null && detail == null && !store.loading && store.fatal.isEmpty()) NavigationBar(containerColor = Color(0xFFF4F4F4)) {
            tabs.forEachIndexed { index, name -> NavigationBarItem(selected = tab == index, onClick = { tab = index; search = ""; filter = "Todos" },
                icon = { Text(listOf("€", "E", "N", "F", "C")[index], color = green) }, label = { Text(name, style = MaterialTheme.typography.labelSmall) }) }
        }
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(horizontal = 20.dp)) {
            Title(db.getJSONObject("company").s("name").ifEmpty { "Gestión de empresa" }, green)
            if (store.saving) LinearProgressIndicator(Modifier.fillMaxWidth())
            when {
                store.loading -> { CircularProgressIndicator(); Text("Cargando datos locales…") }
                store.fatal.isNotEmpty() -> { Text(store.fatal, color = red); Button(onClick = { store.load() }) { Text("REINTENTAR") } }
                edit != null -> {
                    val current = edit!!
                    key(current) { Editor(current, db, store.saving, cancel = { edit = null }, save = { row -> store.put(current.kind, row) { edit = null; notice = "Datos guardados" } }) }
                }
                detail != null -> Detail(detail!!) { detail = null }
                else -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Title(tabs[tab])
                    if (tab == 0 || tab == 2 || tab == 3) RangeButtons(range) { range = it }
                    when (tab) {
                        0 -> Dashboard(db, range)
                        4 -> {
                            val company = db.getJSONObject("company")
                            fields("company", db).forEachIndexed { i, f -> Text("${f.label}: ${company.s(f.key).ifEmpty { "Sin configurar" }}", color = if (i % 2 == 0) red else blue) }
                            Button(enabled = !store.saving, onClick = { edit = Edit("company", company) }) { Text("EDITAR EMPRESA") }
                            HorizontalDivider(color = green)
                            Text(Guardado local en gestion_empresa.json.")
                        }
                        else -> {
                            if (tab == 3) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(selected = !expense, onClick = { expense = false; filter = "Todos" }, label = { Text("Ventas") })
                                FilterChip(selected = expense, onClick = { expense = true; filter = "Todos" }, label = { Text("Gastos") })
                            }
                            val canAdd = kind != "payrolls" || rows(db, "employees").any { it.s("active") == "true" }
                            Button(enabled = !store.saving && canAdd, onClick = { edit = Edit(kind, null) }) {
                                Text(when (kind) { "employees" -> "AÑADIR EMPLEADO"; "payrolls" -> "CREAR NÓMINA"; "sales" -> "AÑADIR FACTURA"; else -> "AÑADIR GASTO" })
                            }
                            if (!canAdd) Text("Primero añade un empleado activo.", color = red)
                            Input("Buscar", search, true) { search = it }
                            val options = if (kind == "employees") listOf("Todos", "Activos", "Inactivos") else listOf("Todos", "Pendientes", "Pagados")
                            Row(horizontalArrangement = Arrangement.spacedBy(5.dp)) { options.forEach { option -> FilterChip(selected = filter == option, onClick = { filter = option }, label = { Text(option) }) } }
                            val selectedRows = rows(db, kind).filter { row ->
                                val matches = listOf("name", "surname", "document", "party", "number", "concept", "employeeName", "employeeDocument").joinToString(" ") { row.s(it) }.contains(search, true)
                                val status = when (filter) { "Activos" -> row.s("active") == "true"; "Inactivos" -> row.s("active") != "true"; "Pendientes" -> row.s("paid") != "true"; "Pagados" -> row.s("paid") == "true"; else -> true }
                                matches && status && (kind == "employees" || inRange(row, kind, range))
                            }.sortedByDescending { if (kind == "employees") it.s("name") else it.s("date") + it.s("period") }
                            Text("${selectedRows.size} registros", color = blue)
                            selectedRows.forEach { row ->
                                Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Summary(kind, row)
                                    val locked = kind == "payrolls" && row.s("paid") == "true"
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        TextButton(onClick = { detail = Edit(kind, row) }) { Text("VER") }
                                        TextButton(enabled = !store.saving && !locked, onClick = { edit = Edit(kind, row) }) { Text("EDITAR") }
                                        TextButton(enabled = !store.saving && !locked, onClick = {
                                            if (kind == "employees" && rows(db, "payrolls").any { it.s("employeeId") == row.s("id") }) notice = "Tiene nóminas asociadas. Márcalo como inactivo en lugar de eliminarlo."
                                            else removal = Removal(kind, row)
                                        }) { Text("BORRAR", color = red) }
                                    }
                                    if (locked) TextButton(enabled = !store.saving, onClick = {
                                        store.put(kind, clone(row).put("paid", "false").put("paidDate", "")) { notice = "Nómina pendiente. Ya puedes editarla." }
                                    }) { Text("VOLVER A PENDIENTE") }
                                }
                                HorizontalDivider(color = green, thickness = 1.dp)
                            }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                }
            }
        }
    }
    if (store.error.isNotEmpty()) AlertDialog(onDismissRequest = { store.clearError() }, title = { Text("Error de guardado") }, text = { Text(store.error) }, confirmButton = { TextButton(onClick = { store.clearError() }) { Text("Aceptar") } })
    if (notice.isNotEmpty()) AlertDialog(onDismissRequest = { notice = "" }, title = { Text("Información") }, text = { Text(notice) }, confirmButton = { TextButton(onClick = { notice = "" }) { Text("Aceptar") } })
    removal?.let { target -> AlertDialog(onDismissRequest = { if (!store.saving) removal = null }, title = { Text("¿Eliminar registro?") }, text = { Text("Se eliminará del archivo local. No se puede deshacer.") },
        confirmButton = { TextButton(enabled = !store.saving, onClick = { store.delete(target.kind, target.row.s("id")) { removal = null } }) { Text("Eliminar") } },
        dismissButton = { TextButton(enabled = !store.saving, onClick = { removal = null }) { Text("Cancelar") } }) }
}
