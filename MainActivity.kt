package ru.brickfinder

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.BitmapDrawable
import android.graphics.LinearGradient
import android.graphics.Shader
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.inputmethod.EditorInfo
import android.text.InputType
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.NumberFormat
import java.util.Locale
import kotlin.concurrent.thread

/** BrickFinder 0.9 dark UI with access code. Product offers are always supplied by the live Cloudflare API. */
class MainActivity : Activity() {
    private val bg = Color.rgb(3, 12, 30)
    private val surface = Color.rgb(12, 25, 48)
    private val surface2 = Color.rgb(24, 43, 72)
    private val blue = Color.rgb(0, 93, 247)
    private val yellow = Color.rgb(255, 214, 8)
    private val muted = Color.rgb(162, 174, 195)
    private val white = Color.WHITE
    private lateinit var queryInput: EditText
    private lateinit var statusText: TextView
    private lateinit var offersPanel: LinearLayout
    private lateinit var photoPreview: ImageView
    private lateinit var selectionText: TextView
    private lateinit var countText: TextView
    private lateinit var cityButton: TextView
    private lateinit var wbToggle: CheckBox
    private lateinit var aliToggle: CheckBox
    private lateinit var avitoToggle: CheckBox
    private lateinit var ozonToggle: CheckBox
    private lateinit var yandexToggle: CheckBox
    private var photoModel: Model? = null
    private var updatingFromPhoto = false
    private var sortedOffers: List<Offer> = emptyList()
    private var ascending = true
    private var favoritesOnly = false
    private var requestSerial = 0
    private val autoHandler = Handler(Looper.getMainLooper())
    private var pendingAutoSearch: Runnable? = null
    private fun queueAutoSearch() {
        pendingAutoSearch?.let { autoHandler.removeCallbacks(it) }
        val raw = queryInput.text.toString().trim()
        // Wait for a full article (usually 5 digits), or a meaningful name.
        if (!Regex("^(?:[A-Za-z]{0,5}\\d{5,7})$").matches(raw) && raw.length < 7) return
        val action = Runnable { if (queryInput.text.toString().trim() == raw) search() }
        pendingAutoSearch = action
        autoHandler.postDelayed(action, 1700L)
    }
    private var sourceCount = 0
    private var sortButton: TextView? = null
    private var searchTabView: TextView? = null
    private var favoritesTabView: TextView? = null
    private val photoRequest = 101
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val provider get() = prefs.getString("provider", DEFAULT_PROVIDER).orEmpty().trimEnd('/')
    private val deliveryCity get() = prefs.getString("delivery_city", "Москва").orEmpty().ifBlank { "Москва" }
    private val money = NumberFormat.getNumberInstance(Locale("ru", "RU"))

    data class Model(val brand: String, val sku: String, val title: String, val pieces: String) {
        fun query() = listOf(brand, sku, title).filter { it.isNotBlank() }.joinToString(" ").trim()
    }
    data class Offer(val title: String, val shop: String, val link: String, val price: Double?, val image: String?, val kind: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.systemUiVisibility = 0
        renderScreen()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density + 0.5f).toInt()
    private fun rounded(fill: Int, corner: Int = 18, stroke: Int? = null): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(fill)
        cornerRadius = dp(corner).toFloat()
        if (stroke != null) setStroke(dp(1), stroke)
    }
    private fun column(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
    private fun row(): LinearLayout = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    private fun text(value: String, size: Float = 15f, color: Int = white, bold: Boolean = false): TextView = TextView(this).apply {
        setText(value); textSize = size; setTextColor(color)
        if (bold) setTypeface(null, Typeface.BOLD)
    }
    private fun margin(top: Int = 0, bottom: Int = 0, left: Int = 0, right: Int = 0) =
        LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(dp(left), dp(top), dp(right), dp(bottom))
        }
    private fun button(label: String, backgroundColor: Int = yellow, textColor: Int = bg): TextView = text(label, 16f, textColor, true).apply {
        gravity = Gravity.CENTER
        background = rounded(backgroundColor, 16)
        setPadding(dp(14), dp(15), dp(14), dp(15))
        isClickable = true
    }
    // Gradient surfaces and dynamic source chips use native Android controls, not bitmap mockups.
    private fun gradient(top: Int, bottom: Int, radius: Int, stroke: Int? = null): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply {
            cornerRadius = dp(radius).toFloat()
            if (stroke != null) setStroke(dp(1), stroke)
        }

    private fun renderScreen() {
        val root = column().apply { background = rounded(bg, 0) }
        val backdrop = FrameLayout(this)
        root.addView(backdrop, LinearLayout.LayoutParams(-1, 0, 1f))
        val cosmic = ImageView(this).apply {
            setImageResource(R.drawable.brickfinder_space)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = .88f
        }
        backdrop.addView(cosmic, FrameLayout.LayoutParams(-1, -1))
        val scroller = ScrollView(this).apply {
            isFillViewport = false
            clipToPadding = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        backdrop.addView(scroller, FrameLayout.LayoutParams(-1, -1))
        setContentView(root)
        val content = column().apply { setPadding(dp(14), dp(9), dp(14), dp(28)) }
        scroller.addView(content)

        // Keep the existing blue mascot asset; the brand is now straight, bigger and sans italic.
        val hero = row().apply {
            background = gradient(Color.rgb(2, 31, 75), Color.rgb(2, 18, 43), 22, Color.rgb(22, 68, 115))
            setPadding(dp(15), dp(12), dp(2), dp(10))
        }
        val heroCopy = column()
        val brick = text("▰", 28f, yellow, true)
        val logoRow = row()
        logoRow.addView(brick)
        val logo = text("Brick", 27f, white, true)
        logoRow.addView(logo)
        logoRow.addView(text("Finder", 27f, yellow, true))
        heroCopy.addView(logoRow)
        // No extra line about comparing analogs, as requested.
        // Header deliberately has no subtitle, per approved design.
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        val mascot = ImageView(this).apply { setImageResource(R.drawable.brickfinder_mascot); scaleType = ImageView.ScaleType.FIT_CENTER }
        hero.addView(mascot, LinearLayout.LayoutParams(dp(110), dp(108)))
        content.addView(hero, margin(bottom = 14))

        val searchBox = row().apply {
            background = gradient(Color.rgb(32, 55, 84), Color.rgb(20, 36, 61), 21, Color.rgb(87, 122, 165))
            setPadding(dp(13), dp(2), dp(10), dp(2))
            elevation = dp(4).toFloat()
        }
        searchBox.addView(text("⌕", 27f, white), LinearLayout.LayoutParams(dp(30), -2))
        queryInput = EditText(this).apply {
            hint = "Название набора или артикул"
            setHintTextColor(muted); setTextColor(white); textSize = 13f
            isSingleLine = true; background = null
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setOnEditorActionListener { _, actionId, _ ->
                if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                    pendingAutoSearch?.let { autoHandler.removeCallbacks(it) }
                    search()
                    true
                } else false
            }
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!updatingFromPhoto) { photoModel = null; queueAutoSearch() } }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBox.addView(queryInput, LinearLayout.LayoutParams(0, dp(49), 1f))
        content.addView(searchBox, margin(bottom = 10))
        val photoBtn = button("▣  Поиск по фото").apply {
            textSize = 16f
            background = gradient(Color.rgb(255, 236, 18), Color.rgb(255, 197, 0), 19)
            elevation = dp(5).toFloat()
            setOnClickListener { pickPhoto() }
        }
        content.addView(photoBtn, margin(bottom = 6))
        photoPreview = ImageView(this).apply { visibility = View.GONE; adjustViewBounds = true; maxHeight = dp(220); scaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(photoPreview)
        selectionText = text("", 12f, yellow)
        content.addView(selectionText)

        val heading = row()
        heading.addView(text("Где искать", 20f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        val info = text("ⓘ  Как сравниваем?", 11f, Color.rgb(75, 184, 255)).apply {
            setOnClickListener { showComparisonInfo() }
        }
        heading.addView(info)
        content.addView(heading, margin(top = 14, bottom = 8))
        cityButton = text("⌖  Город доставки: $deliveryCity  ▾", 13f, white, true).apply {
            background = gradient(Color.rgb(30, 54, 85), Color.rgb(16, 35, 65), 17, Color.rgb(77, 111, 156))
            setPadding(dp(13), dp(11), dp(13), dp(11))
            setOnClickListener { chooseDeliveryCity() }
            contentDescription = "Выбрать город доставки"
        }
        content.addView(cityButton, margin(bottom = 9))
        // Exactly the approved layout: three marketplaces in the first row, two in the second.
        val selectors = column()
        val r1 = row()
        ozonToggle = sourceToggle("Ozon", "◉", Color.rgb(10, 108, 245), true)
        wbToggle = sourceToggle("Wildberries", "WB", Color.rgb(171, 16, 194), true)
        aliToggle = sourceToggle("AliExpress", "◡", Color.rgb(242, 67, 24), true)
        r1.addView(ozonToggle, LinearLayout.LayoutParams(0, dp(49), 1f))
        r1.addView(wbToggle, LinearLayout.LayoutParams(0, dp(49), 1.28f).apply { leftMargin = dp(5) })
        r1.addView(aliToggle, LinearLayout.LayoutParams(0, dp(49), 1.15f).apply { leftMargin = dp(5) })
        selectors.addView(r1)
        val r2 = row()
        yandexToggle = sourceToggle("Яндекс Маркет", "Я", Color.rgb(248, 200, 14), true)
        avitoToggle = sourceToggle("Авито", "●", Color.rgb(19, 188, 169), false)
        r2.addView(yandexToggle, LinearLayout.LayoutParams(0, dp(49), 1f))
        r2.addView(avitoToggle, LinearLayout.LayoutParams(0, dp(49), 1f).apply { leftMargin = dp(7) })
        selectors.addView(r2, margin(top = 7))
        content.addView(selectors)

        val go = button("Найти предложения", blue, white).apply {
            textSize = 17f
            background = gradient(Color.rgb(22, 123, 255), Color.rgb(0, 66, 210), 21, Color.rgb(29, 201, 255))
            elevation = dp(6).toFloat()
            setOnClickListener { search() }
        }
        content.addView(go, margin(top = 26, bottom = 16, left = 12, right = 12))
        statusText = text("Выбери площадки и начни поиск.", 11f, muted)
        content.addView(statusText, margin(bottom = 12, left = 3, right = 3))
        val resultsHeading = row()
        countText = text("Найденные предложения", 18f, white, true)
        resultsHeading.addView(countText, LinearLayout.LayoutParams(0, -2, 1f))
        val sort = text("⇅  Сначала выгодные ▾", 11f, white, true).apply {
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = gradient(Color.rgb(31, 50, 79), Color.rgb(14, 30, 54), 15, Color.rgb(83, 112, 151))
            setOnClickListener {
                ascending = !ascending
                text = if (ascending) "⇅  Сначала выгодные ▾" else "⇅  Сначала дорогие ▾"
                refreshOffers()
            }
        }
        sortButton = sort
        resultsHeading.addView(sort)
        content.addView(resultsHeading, margin(bottom = 11))
        offersPanel = column()
        content.addView(offersPanel)
        offersPanel.addView(text("Здесь появятся реальные предложения выбранных магазинов.", 12f, muted))

        val bottom = row().apply {
            setPadding(dp(9), dp(7), dp(9), dp(9))
            background = gradient(Color.rgb(24, 42, 71), Color.rgb(6, 18, 35), 22, Color.rgb(52, 86, 121))
        }
        val searchTab = text("⌂\nПоиск", 13f, white, true).apply {
            gravity = Gravity.CENTER
            background = gradient(Color.rgb(17, 91, 181), Color.rgb(3, 42, 98), 16, Color.rgb(31, 131, 237))
            setOnClickListener { favoritesOnly = false; updateTabs(); refreshOffers() }
        }
        searchTabView = searchTab
        bottom.addView(searchTab, LinearLayout.LayoutParams(0, dp(53), 1f))
        val favTab = text("♡\nИзбранное", 12f, white).apply {
            gravity = Gravity.CENTER
            setOnClickListener { favoritesOnly = true; updateTabs(); refreshOffers() }
        }
        favoritesTabView = favTab
        bottom.addView(favTab, LinearLayout.LayoutParams(0, dp(53), 1f))
        val notificationTab = text("♧\nУведомления", 11f, white).apply {
            gravity = Gravity.CENTER
            setOnClickListener { Toast.makeText(this@MainActivity, "Уведомления пока не подключены", Toast.LENGTH_SHORT).show() }
        }
        bottom.addView(notificationTab, LinearLayout.LayoutParams(0, dp(53), 1f))
        val settingsTab = text("♙\nПрофиль", 12f, white).apply {
            gravity = Gravity.CENTER
            contentDescription = "Настройки сервера"
            setOnClickListener { showSettings() }
        }
        bottom.addView(settingsTab, LinearLayout.LayoutParams(0, dp(53), 1f))
        root.addView(bottom)
    }

    private fun updateTabs() {
        searchTabView?.background = if (!favoritesOnly) gradient(Color.rgb(17, 91, 181), Color.rgb(3, 42, 98), 16) else rounded(Color.TRANSPARENT, 16)
        favoritesTabView?.background = if (favoritesOnly) gradient(Color.rgb(17, 91, 181), Color.rgb(3, 42, 98), 16) else rounded(Color.TRANSPARENT, 16)
    }

    private fun sourceToggle(label: String, initial: String, color: Int, checkedByDefault: Boolean): CheckBox = CheckBox(this).apply {
        setButtonDrawable(null as Drawable?)
        text = "${if (checkedByDefault) "☑" else "□"} $initial $label"
        textSize = when (label) {
            "Wildberries", "Яндекс Маркет", "AliExpress" -> 10.5f
            else -> 12f
        }
        setTextColor(white)
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER_VERTICAL
        isChecked = prefs.getBoolean("source_$label", checkedByDefault)
        text = "${if (isChecked) "☑" else "□"} $initial $label"
        buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
        background = gradient(if (isChecked) Color.rgb(17, 58, 119) else surface2, Color.rgb(9, 25, 48), 14, if (isChecked) Color.rgb(24, 140, 255) else Color.rgb(65, 87, 121))
        setPadding(dp(3), 0, dp(2), 0)
        setOnCheckedChangeListener { tile, active ->
            tile.text = "${if (active) "☑" else "□"} $initial $label"
            tile.background = gradient(if (active) Color.rgb(17, 58, 119) else surface2, Color.rgb(9, 25, 48), 14, if (active) Color.rgb(24, 140, 255) else Color.rgb(65, 87, 121))
            prefs.edit().putBoolean("source_$label", active).apply()
        }
    }
    private fun showComparisonInfo() {
        android.app.AlertDialog.Builder(this)
            .setTitle("Как BrickFinder сравнивает предложения")
            .setMessage("Полные наборы выводятся выше отдельных деталей. Внутри групп товары сортируются по цене.\n\nOzon показывает цену по карте Ozon, если в Cloudflare подключён TOOLZERHUB_API_KEY. Без него Ozon, как и Яндекс Маркет, ищет страницы товаров без подтверждённой цены. Цены и наличие проверяй в магазине.\n\nРейтинги, скидки и стоимость доставки не показываются без подтверждённых данных. Итоговую цену проверяй в магазине.")
            .setPositiveButton("Понятно", null).show()
    }
    private fun disabledSource(label: String) = text(label, 12f, muted).apply {
        gravity = Gravity.CENTER; background = rounded(Color.rgb(20, 26, 39), 14, Color.rgb(49, 57, 72))
    }
    private fun chooseDeliveryCity() {
        val cities = arrayOf("Москва", "Санкт-Петербург", "Тверь", "Казань", "Екатеринбург", "Новосибирск", "Краснодар", "Нижний Новгород", "Другой город…")
        android.app.AlertDialog.Builder(this)
            .setTitle("Город доставки")
            .setSingleChoiceItems(cities, cities.indexOf(deliveryCity)) { dialog, which ->
                dialog.dismiss()
                if (which == cities.lastIndex) {
                    val field = EditText(this).apply {
                        hint = "Например, Ростов-на-Дону"
                        setSingleLine(true)
                        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                    }
                    val wrapper = column().apply { setPadding(dp(22), dp(8), dp(22), 0); addView(field) }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("Введите город")
                        .setView(wrapper)
                        .setPositiveButton("Сохранить") { _, _ -> setDeliveryCity(field.text.toString()) }
                        .setNegativeButton("Отмена", null).show()
                } else setDeliveryCity(cities[which])
            }
            .setNegativeButton("Отмена", null).show()
    }

    private fun setDeliveryCity(raw: String) {
        val value = raw.trim().replace(Regex("\\s+"), " ")
        if (value.length !in 2..60 || !value.all { it.isLetter() || it == ' ' || it == '-' || it == 'ё' || it == 'Ё' }) {
            Toast.makeText(this, "Укажи корректное название города", Toast.LENGTH_LONG).show()
            return
        }
        prefs.edit().putString("delivery_city", value).apply()
        cityButton.text = "⌖  Город доставки: $value  ▾"
        statusText.text = "Город выбран: $value. Проверь возможность доставки в карточке магазина."
    }

    private fun showSettings() {
        val address = EditText(this).apply {
            setSingleLine(true); setText(provider); hint = "https://...workers.dev"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
        }
        val access = EditText(this).apply {
            setSingleLine(true)
            hint = "Личный код доступа (не API.SH ключ)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(prefs.getString("access_token", "").orEmpty())
        }
        val form = column().apply {
            setPadding(dp(24), dp(8), dp(24), dp(8))
            addView(text("Адрес сервера", 14f, muted))
            addView(address)
            addView(text("Код доступа к BrickFinder", 14f, muted))
            addView(access)
        }
        android.app.AlertDialog.Builder(this)
            .setTitle("Защищённый поиск")
            .setMessage("Код доступа задаётся в Cloudflare как BRICKFINDER_ACCESS_TOKEN. Не вводи сюда API.SH ключ.")
            .setView(form)
            .setPositiveButton("Сохранить") { _, _ ->
                val next = address.text.toString().trim().trimEnd('/')
                val token = access.text.toString().trim()
                if (!next.startsWith("https://") || token.isEmpty()) {
                    statusText.text = "Введи HTTPS-адрес и личный код доступа."
                } else {
                    prefs.edit().putString("provider", next).putString("access_token", token).apply()
                    statusText.text = "Настройки защищённого поиска сохранены."
                }
            }.setNegativeButton("Отмена", null).show()
    }
    private fun pickPhoto() {
        val pick = Intent(Intent.ACTION_GET_CONTENT).apply { type = "image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(Intent.createChooser(pick, "Выбери фото конструктора"), photoRequest)
    }
    @Deprecated("Legacy picker supports Android 8+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != photoRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        photoModel = null
        photoPreview.setImageURI(uri); photoPreview.visibility = View.VISIBLE
        selectionText.text = "Распознаю коробку…"
        try {
            recognizer.process(InputImage.fromFilePath(this, uri)).addOnSuccessListener {
                val model = identifyBox(it.text)
                if (model == null) { selectionText.text = "Не смог определить артикул — введи название вручную."; return@addOnSuccessListener }
                photoModel = model
                updatingFromPhoto = true
                queryInput.setText(model.query())
                updatingFromPhoto = false
                selectionText.text = "По фото: ${model.brand} ${model.sku}" + if (model.pieces.isNotBlank()) " · ${model.pieces} деталей" else ""
                search()
            }.addOnFailureListener { selectionText.text = "Не удалось распознать фото." }
        } catch (_: Exception) { selectionText.text = "Не удалось открыть фотографию." }
    }
    private fun identifyBox(raw: String): Model? {
        val clean = raw.replace('–', '-').replace('—', '-').uppercase(Locale.ROOT)
        val brand = listOf("FALAGIL", "MOULD KING", "REOBRIX", "SEMBO", "CADA", "LEPIN", "LEGO", "CBOX", "JIESTAR", "PANLOS", "LOZ")
            .firstOrNull { clean.contains(it) }.orEmpty()
        val title = listOf("SPACE ACADEMY", "FERRARI DAYTONA SP3", "TECHNIC", "STAR WARS", "CREATOR", "CITY")
            .firstOrNull { clean.contains(it) }.orEmpty()
        val pieces = Regex("(?i)(\\d{1,3}(?:[ ,.]\\d{3})+|\\d{3,5})\\s*(PCS|PIECES|ДЕТАЛ)")
            .find(clean)?.groupValues?.get(1)?.filter { it.isDigit() }.orEmpty()
        val ids = Regex("(?<![A-Z0-9])([A-Z]{1,5}[ -]?\\d{3,7}|\\d{4,7})(?![A-Z0-9])")
            .findAll(clean.replace("\n", " ")).map { it.groupValues[1].replace(" ", "").replace("-", "") }
            .filterNot { it == pieces || it == "2025" || it == "2026" }.toList()
        val sku = ids.firstOrNull { it.any(Char::isLetter) && it.any(Char::isDigit) } ?: ids.firstOrNull().orEmpty()
        if (sku.isBlank() && brand.isBlank() && title.isBlank()) return null
        return Model(brand, sku, title, pieces)
    }
    private fun search() {
        pendingAutoSearch?.let { autoHandler.removeCallbacks(it) }
        pendingAutoSearch = null
        val query = photoModel?.query()?.takeIf { it.isNotBlank() } ?: queryInput.text.toString().trim()
        if (query.length < 2) { statusText.text = "Введи артикул или выбери фотографию."; return }
        val selected = listOfNotNull(if (wbToggle.isChecked) "wildberries" else null,
            if (aliToggle.isChecked) "aliexpress" else null, if (avitoToggle.isChecked) "avito" else null,
            if (ozonToggle.isChecked) "ozon" else null, if (yandexToggle.isChecked) "yandexmarket" else null)
        if (selected.isEmpty()) { statusText.text = "Выбери хотя бы одну подключённую площадку."; return }
        if (!provider.startsWith("https://")) { statusText.text = "Укажи HTTPS-адрес сервера в настройках ⚙."; return }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(queryInput.windowToken, 0)
        favoritesOnly = false
        updateTabs()
        sourceCount = selected.size
        val serial = ++requestSerial
        statusText.text = "Сравниваю цены на ${selected.size} площадках…"
        offersPanel.removeAllViews()
        offersPanel.addView(text("Загружаю предложения…", 14f, muted))
        countText.text = "Предложения"
        val requestedProvider = provider
        val requestedCity = deliveryCity
        val accessToken = prefs.getString("access_token", "").orEmpty()
        if (accessToken.isBlank()) { statusText.text = "Открой настройки ⚙ и введи личный код доступа."; return }
        thread {
            try {
                val endpoint = URL("$requestedProvider/api/search?q=${enc(query)}&city=${enc(requestedCity)}&sources=${enc(selected.joinToString(","))}")
                val connection = endpoint.openConnection() as HttpURLConnection
                connection.connectTimeout = 12000; connection.readTimeout = 65000
                connection.setRequestProperty("x-brickfinder-token", accessToken)
                try {
                    val code = connection.responseCode
                    if (code == 401) throw IllegalStateException("Неверный код доступа BrickFinder")
                    if (code == 503) throw IllegalStateException("Сервер ещё не настроен: проверь секреты Cloudflare")
                    if (code == 502) {
                        val diagnostic = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                        val errors = runCatching { JSONObject(diagnostic).optJSONArray("sources") }.getOrNull()
                        val summary = (0 until (errors?.length() ?: 0)).mapNotNull { i ->
                            errors?.optJSONObject(i)?.let { o -> o.optString("error").takeIf { it.isNotBlank() }?.let { "${o.optString("source")}: $it" } }
                        }.joinToString("; ")
                        throw IllegalStateException(if (summary.isNotBlank()) summary else "Выбранные площадки не ответили")
                    }
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val resolvedName = root.optString("catalog_name").takeIf { it.isNotBlank() && it != "null" }
                    val providerErrors = root.optJSONArray("sources")
                    val failures = (0 until (providerErrors?.length() ?: 0)).mapNotNull { i ->
                        providerErrors?.optJSONObject(i)?.let { o ->
                            o.optString("error").takeIf { it.isNotBlank() }?.let { "${o.optString("source")}: $it" }
                        }
                    }
                    val partial = failures.isNotEmpty()
                    val arr = root.optJSONArray("items")
                    val parsed = mutableListOf<Offer>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val link = item.optString("url")
                        if (!validCard(link)) continue
                        val amount = if (item.isNull("price") || !item.has("price")) null else item.optDouble("price").takeIf { !it.isNaN() && it > 0.0 }
                        parsed.add(Offer(item.optString("title", "Конструктор"), item.optString("shop", "Магазин"), link,
                            amount, item.optString("image").takeIf { it.startsWith("https://") }, item.optString("match_type", "set")))
                    }
                    runOnUiThread {
                        if (serial != requestSerial) return@runOnUiThread
                        sortedOffers = parsed.distinctBy { it.link }
                        statusText.text = (resolvedName?.let { "Набор: $it. " } ?: "") +
                            (if (parsed.isEmpty()) "Подходящих карточек пока нет." else "Сравнил предложения выбранных магазинов. Цены без данных — внизу; доставка не проверена.") +
                            (if (partial) " Ошибки: ${failures.joinToString("; ")}" else "")
                        refreshOffers()
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) { runOnUiThread {
                if (serial != requestSerial) return@runOnUiThread
                offersPanel.removeAllViews()
                statusText.text = if (e is java.net.SocketTimeoutException) "Сервер долго отвечает. Повтори поиск — остальные источники должны отработать независимо." else "Не удалось получить предложения: ${e.localizedMessage ?: "ошибка соединения"}"
            } }
        }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    private fun openMarketplaceSearch(market: String) {
        val q = queryInput.text.toString().trim().ifBlank { photoModel?.query().orEmpty() }
        if (q.isBlank()) { statusText.text = "Сначала введи артикул или название набора."; return }
        val site = if (market == "ozon") "https://www.ozon.ru/search/?text=${enc("LEGO $q")}" else "https://market.yandex.ru/search?text=${enc("LEGO $q")}" 
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(site)))
    }
    private fun validCard(value: String): Boolean = try {
        val uri = Uri.parse(value); if (uri.scheme != "https") throw IllegalArgumentException("Only HTTPS")
        val host = uri.host.orEmpty().lowercase(Locale.ROOT)
        val path = uri.path.orEmpty().lowercase(Locale.ROOT)
        when {
            host == "www.wildberries.ru" || host == "wildberries.ru" -> path.matches(Regex("/catalog/\\d+/detail(?:\\.aspx)?/?"))
            host == "www.avito.ru" || host == "avito.ru" -> path.matches(Regex(".*/[^/]+_\\d{5,}/?"))
            host == "www.aliexpress.com" || host == "aliexpress.com" || host == "aliexpress.ru" || host == "www.aliexpress.ru" -> path.matches(Regex("/item/\\d+\\.html/?"))
            host == "www.ozon.ru" || host == "ozon.ru" -> path.contains("/product/")
            host == "market.yandex.ru" -> path.contains("/product/") || path.contains("/product--")
            else -> false
        }
    } catch (_: Exception) { false }
    private fun saved(): MutableSet<String> = prefs.getStringSet("favorites", emptySet())?.toMutableSet() ?: mutableSetOf()
    private fun toggleSaved(link: String) {
        val set = saved()
        if (!set.add(link)) set.remove(link)
        prefs.edit().putStringSet("favorites", set).apply()
        refreshOffers()
    }
    // Client-side fallback: classify obvious small replacement packs even if the
    // backend response contains an older "set" label (e.g. a cached response).
    private fun isAccessory(offer: Offer): Boolean {
        if (offer.kind == "accessory") return true
        val title = offer.title.lowercase()
        val obviousPart = Regex("""запчаст|детал[ьи]|аксессуар|колес[оа]|шин[аы]|панел[ьи]|спойлер|подсветк|подставк|наклейк|spare parts|replacement|light kit|display stand|wheel|sticker""").containsMatchIn(title)
        val tinyPack = Regex("""(?:^|[^0-9a-zа-я])(?:[1-9]|1[0-9]|20)\s*(?:pcs?\b|pieces?\b|шт\.?\b|штук\b|детал(?:ь|и|ей)\b)|(?:pcs?\b|pieces?\b)\s*(?:[1-9]|1[0-9]|20)(?!\d)""", RegexOption.IGNORE_CASE).containsMatchIn(title)
        return obviousPart || tinyPack
    }
    // ONE shared comparison across all selected marketplaces, never one sort per shop.
    private fun refreshOffers() {
        if (!::offersPanel.isInitialized) return
        offersPanel.removeAllViews()
        val favs = saved()
        val filtered = sortedOffers.filter { !favoritesOnly || favs.contains(it.link) }
            .sortedWith(Comparator<Offer> { a, b ->
                val kindOrder = (if (isAccessory(a)) 1 else 0).compareTo(if (isAccessory(b)) 1 else 0)
                if (kindOrder != 0) kindOrder
                else {
                    // Never rank missing/unverified prices above known RUB prices.
                    val missing = (a.price == null).compareTo(b.price == null)
                    if (missing != 0) missing
                    else if (ascending) (a.price ?: Double.POSITIVE_INFINITY).compareTo(b.price ?: Double.POSITIVE_INFINITY)
                    else (b.price ?: Double.NEGATIVE_INFINITY).compareTo(a.price ?: Double.NEGATIVE_INFINITY)
                }
            })
        countText.text = if (favoritesOnly) "Избранное (${filtered.size})" else "Предложения (${filtered.size})"
        if (filtered.isEmpty()) {
            offersPanel.addView(text(if (favoritesOnly) "В текущих результатах нет избранного." else "Пока ничего не найдено. Попробуй другой запрос или магазин.", 12f, muted))
            return
        }
        val lowestSetPrice = filtered.filter { !isAccessory(it) }.mapNotNull { it.price }.minOrNull()
        filtered.forEach { offer ->
            val best = !favoritesOnly && lowestSetPrice != null && !isAccessory(offer) && offer.price == lowestSetPrice
            offersPanel.addView(offerCard(offer, favs.contains(offer.link), best), margin(bottom = 8))
        }
    }

    private fun offerCard(offer: Offer, fav: Boolean, best: Boolean): View {
        val card = row().apply {
            gravity = Gravity.CENTER_VERTICAL
            background = gradient(Color.rgb(25, 43, 66), Color.rgb(10, 23, 42), 17, if (best) Color.rgb(32, 143, 245) else Color.rgb(66, 93, 120))
            setPadding(dp(7), dp(7), dp(7), dp(7))
            minimumHeight = dp(111)
        }
        val imageFrame = FrameLayout(this)
        val image = ImageView(this).apply {
            background = rounded(surface2, 11)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        imageFrame.addView(image, FrameLayout.LayoutParams(-1, -1))
        loadOfferImage(offer.image, image)
        card.addView(imageFrame, LinearLayout.LayoutParams(dp(103), dp(106)))
        val details = column().apply { setPadding(dp(9), dp(1), dp(4), 0) }
        val title = text(offer.title, 12.5f, white, true).apply {
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
        }
        details.addView(title)
        val shopColor = when (offer.shop.lowercase(Locale.ROOT)) {
            "ozon" -> Color.rgb(15, 91, 239)
            "wildberries" -> Color.rgb(131, 31, 184)
            "aliexpress" -> Color.rgb(220, 62, 19)
            "яндекс маркет" -> Color.rgb(149, 120, 12)
            else -> Color.rgb(13, 120, 112)
        }
        val label = text(offer.shop, 10f, white, true).apply {
            background = rounded(shopColor, 8)
            setPadding(dp(5), dp(3), dp(5), dp(3))
        }
        details.addView(label, margin(top = 6))
        details.addView(text(if (isAccessory(offer)) "Деталь / аксессуар" else if (best) "★ Лучшая цена набора" else "Полный набор", 9.5f, if (best) yellow else muted), margin(top = 5))
        card.addView(details, LinearLayout.LayoutParams(0, -2, 1f))
        val actions = column().apply { gravity = Gravity.RIGHT }
        val like = text(if (fav) "♥" else "♡", 22f, if (fav) yellow else white).apply {
            gravity = Gravity.RIGHT
            setOnClickListener { toggleSaved(offer.link) }
            contentDescription = "Добавить в избранное"
        }
        actions.addView(like)
        val amount = text(offer.price?.let { "${money.format(it)} ₽" } ?: "Цена не\nуказана", if (offer.price == null) 10f else 15f, white, true).apply {
            gravity = Gravity.RIGHT
            setMaxLines(2)
        }
        actions.addView(amount, margin(top = 3))
        val open = button("Открыть ↗", yellow, bg).apply {
            textSize = 10f
            setPadding(dp(7), dp(8), dp(7), dp(8))
            setOnClickListener { openOffer(offer.link) }
        }
        actions.addView(open, margin(top = 8))
        card.addView(actions, LinearLayout.LayoutParams(dp(104), -2))
        return card
    }
    private fun loadOfferImage(imageUrl: String?, view: ImageView) {
        if (imageUrl.isNullOrBlank()) return
        val src = when {
            imageUrl.startsWith("//") -> "https:$imageUrl"
            imageUrl.startsWith("http://") -> "https://${imageUrl.removePrefix("http://")}" 
            else -> imageUrl
        }
        if (!src.startsWith("https://")) return
        view.tag = src
        thread {
            try {
                val connection = URL(src).openConnection() as HttpURLConnection
                connection.connectTimeout = 6000
                connection.readTimeout = 9000
                connection.instanceFollowRedirects = true
                connection.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/120.0 Mobile Safari/537.36")
                connection.setRequestProperty("Accept", "image/jpeg,image/png,image/webp,image/*;q=0.8")
                try {
                    if (connection.responseCode !in 200..299) return@thread
                    val bitmap: Bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return@thread
                    runOnUiThread {
                        if (view.parent != null && view.tag == src) view.setImageBitmap(bitmap)
                    }
                } finally { connection.disconnect() }
            } catch (_: Exception) { /* Leave image placeholder; results are still usable. */ }
        }
    }
    private fun openOffer(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { statusText.text = "Не удалось открыть карточку товара." }
    }
    override fun onDestroy() { pendingAutoSearch?.let { autoHandler.removeCallbacks(it) }; recognizer.close(); super.onDestroy() }
    companion object { private const val DEFAULT_PROVIDER = "https://brickfinder-api.lehalepeha8658.workers.dev" }
}

    override fun onDestroy() { super.onDestroy(); recognizer.close() }
}
