package ru.brickfinder

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
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

/** BrickFinder 0.8 dark UI. Product offers are always supplied by the live Cloudflare API. */
class MainActivity : Activity() {
    private val bg = Color.rgb(10, 15, 25)
    private val surface = Color.rgb(24, 32, 48)
    private val surface2 = Color.rgb(32, 43, 62)
    private val blue = Color.rgb(21, 92, 225)
    private val yellow = Color.rgb(255, 211, 22)
    private val muted = Color.rgb(162, 174, 195)
    private val white = Color.WHITE
    private lateinit var queryInput: EditText
    private lateinit var statusText: TextView
    private lateinit var offersPanel: LinearLayout
    private lateinit var photoPreview: ImageView
    private lateinit var selectionText: TextView
    private lateinit var countText: TextView
    private lateinit var wbToggle: CheckBox
    private lateinit var aliToggle: CheckBox
    private lateinit var avitoToggle: CheckBox
    private var photoModel: Model? = null
    private var updatingFromPhoto = false
    private var sortedOffers: List<Offer> = emptyList()
    private var ascending = true
    private var favoritesOnly = false
    private val photoRequest = 101
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val prefs by lazy { getPreferences(MODE_PRIVATE) }
    private val provider get() = prefs.getString("provider", DEFAULT_PROVIDER).orEmpty().trimEnd('/')
    private val money = NumberFormat.getNumberInstance(Locale("ru", "RU"))

    data class Model(val brand: String, val sku: String, val title: String, val pieces: String) {
        fun query() = listOf(brand, sku, title).filter { it.isNotBlank() }.joinToString(" ").trim()
    }
    data class Offer(val title: String, val shop: String, val link: String, val price: Double?, val image: String?)

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
    private fun renderScreen() {
        val root = column().apply { setBackgroundColor(bg) }
        setContentView(root)
        val scroller = ScrollView(this).apply { isFillViewport = false }
        root.addView(scroller, LinearLayout.LayoutParams(-1, 0, 1f))
        val content = column().apply { setPadding(dp(16), dp(14), dp(16), dp(22)) }
        scroller.addView(content)

        // Header: user-supplied blue astronaut mascot and distinctive dark/yellow branding.
        val hero = row().apply {
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.rgb(5, 45, 111), Color.rgb(6, 20, 43))).apply { cornerRadius = dp(22).toFloat() }
            setPadding(dp(18), dp(16), dp(4), dp(10))
        }
        val heroCopy = column()
        val brand = text("BrickFinder", 27f, white, true)
        heroCopy.addView(brand)
        heroCopy.addView(text("Найди свой конструктор\nпо лучшей цене", 13f, Color.rgb(217, 231, 255)), margin(top = 9))
        heroCopy.addView(text("LEGO и совместимые аналоги", 11f, yellow, true), margin(top = 11))
        hero.addView(heroCopy, LinearLayout.LayoutParams(0, -2, 1f))
        val mascot = ImageView(this).apply { setImageResource(R.drawable.brickfinder_mascot); scaleType = ImageView.ScaleType.FIT_CENTER }
        hero.addView(mascot, LinearLayout.LayoutParams(dp(128), dp(148)))
        content.addView(hero, margin(bottom = 15))

        val searchBox = row().apply { background = rounded(surface2, 16, Color.rgb(54, 70, 97)); setPadding(dp(14), dp(4), dp(12), dp(4)) }
        searchBox.addView(text("⌕", 29f, muted), LinearLayout.LayoutParams(dp(34), -2))
        queryInput = EditText(this).apply {
            hint = "Название или артикул, например 42143"
            setHintTextColor(muted)
            setTextColor(white)
            textSize = 14f
            isSingleLine = true
            background = null
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { if (!updatingFromPhoto) photoModel = null }
                override fun afterTextChanged(s: Editable?) {}
            })
        }
        searchBox.addView(queryInput, LinearLayout.LayoutParams(0, dp(50), 1f))
        content.addView(searchBox, margin(bottom = 10))
        val photoBtn = button("▣   Поиск по фото")
        photoBtn.setOnClickListener { pickPhoto() }
        content.addView(photoBtn, margin(bottom = 9))
        photoPreview = ImageView(this).apply { visibility = View.GONE; adjustViewBounds = true; maxHeight = dp(220); scaleType = ImageView.ScaleType.FIT_CENTER }
        content.addView(photoPreview)
        selectionText = text("", 12f, yellow)
        content.addView(selectionText)

        val sourcesHeading = row()
        sourcesHeading.addView(text("Где искать", 19f, white, true), LinearLayout.LayoutParams(0, -2, 1f))
        sourcesHeading.addView(text("Тверь  •  доставка уточняется", 11f, muted))
        content.addView(sourcesHeading, margin(top = 14, bottom = 12))
        val selectors = column()
        val firstLine = row()
        wbToggle = sourceToggle("Wildberries", true)
        aliToggle = sourceToggle("AliExpress", true)
        firstLine.addView(wbToggle, LinearLayout.LayoutParams(0, dp(53), 1f))
        firstLine.addView(aliToggle, LinearLayout.LayoutParams(0, dp(53), 1f).apply { leftMargin = dp(8) })
        selectors.addView(firstLine)
        val unavailable = row()
        unavailable.addView(disabledSource("Ozon · скоро"), LinearLayout.LayoutParams(0, dp(48), 1f))
        unavailable.addView(disabledSource("Яндекс Маркет · скоро"), LinearLayout.LayoutParams(0, dp(48), 1f).apply { leftMargin = dp(8) })
        selectors.addView(unavailable, margin(top = 8))
        avitoToggle = sourceToggle("Авито · дополнительно", false)
        selectors.addView(avitoToggle, margin(top = 8))
        content.addView(selectors)

        val go = button("Найти предложения", blue, white)
        go.setOnClickListener { search() }
        content.addView(go, margin(top = 15, bottom = 8))
        statusText = text("Поиск использует твой Cloudflare Worker.", 12f, muted)
        content.addView(statusText, margin(bottom = 14))

        val resultsHeading = row()
        countText = text("Предложения", 21f, white, true)
        resultsHeading.addView(countText, LinearLayout.LayoutParams(0, -2, 1f))
        val sort = text("⇅  По цене", 13f, yellow, true).apply { setPadding(dp(12), dp(10), dp(12), dp(10)); background = rounded(surface2, 14); setOnClickListener { ascending = !ascending; refreshOffers() } }
        resultsHeading.addView(sort)
        content.addView(resultsHeading, margin(bottom = 12))
        offersPanel = column()
        content.addView(offersPanel)
        offersPanel.addView(text("Здесь появятся проверенные карточки наборов с ценами и ссылками на магазины.", 13f, muted))

        val bottom = row().apply {
            setPadding(dp(20), dp(10), dp(20), dp(12))
            background = rounded(surface, 18)
        }
        val searchTab = text("⌕  Поиск", 15f, yellow, true).apply { gravity = Gravity.CENTER; setOnClickListener { favoritesOnly = false; refreshOffers() } }
        bottom.addView(searchTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        val favTab = text("♡  Избранное", 15f, white).apply { gravity = Gravity.CENTER; setOnClickListener { favoritesOnly = true; refreshOffers() } }
        bottom.addView(favTab, LinearLayout.LayoutParams(0, dp(44), 1f))
        val settingsTab = text("⚙", 23f, muted).apply { gravity = Gravity.CENTER; contentDescription = "Настройки сервера"; setOnClickListener { showSettings() } }
        bottom.addView(settingsTab, LinearLayout.LayoutParams(dp(50), dp(44)))
        root.addView(bottom)
    }

    private fun sourceToggle(label: String, checked: Boolean): CheckBox = CheckBox(this).apply {
        text = label; textSize = 13f; setTextColor(white); isChecked = checked
        buttonTintList = android.content.res.ColorStateList.valueOf(yellow)
        background = rounded(surface2, 14)
        setPadding(dp(10), 0, dp(8), 0)
    }
    private fun disabledSource(label: String) = text(label, 12f, muted).apply {
        gravity = Gravity.CENTER; background = rounded(Color.rgb(20, 26, 39), 14, Color.rgb(49, 57, 72))
    }
    private fun showSettings() {
        val field = EditText(this).apply { setSingleLine(true); setText(provider); hint = "https://...workers.dev" }
        val wrap = LinearLayout(this).apply { setPadding(dp(24), dp(6), dp(24), dp(4)); addView(field) }
        android.app.AlertDialog.Builder(this).setTitle("Адрес источника цен")
            .setMessage("Сюда вводится HTTPS-адрес Cloudflare Worker, не API-ключ.")
            .setView(wrap).setPositiveButton("Сохранить") { _, _ ->
                val next = field.text.toString().trim().trimEnd('/')
                if (next.startsWith("https://")) { prefs.edit().putString("provider", next).apply(); statusText.text = "Источник обновлён." }
                else statusText.text = "Нужен HTTPS-адрес сервера."
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
        val query = photoModel?.query()?.takeIf { it.isNotBlank() } ?: queryInput.text.toString().trim()
        if (query.length < 2) { statusText.text = "Введи артикул или выбери фотографию."; return }
        val selected = listOfNotNull(if (wbToggle.isChecked) "wildberries" else null,
            if (aliToggle.isChecked) "aliexpress" else null, if (avitoToggle.isChecked) "avito" else null)
        if (selected.isEmpty()) { statusText.text = "Выбери хотя бы одну подключённую площадку."; return }
        if (!provider.startsWith("https://")) { statusText.text = "Укажи HTTPS-адрес сервера в настройках ⚙."; return }
        (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(queryInput.windowToken, 0)
        statusText.text = "Ищу подходящие конструкторы…"
        offersPanel.removeAllViews()
        offersPanel.addView(text("Загружаю предложения…", 14f, muted))
        countText.text = "Предложения"
        val requestedProvider = provider
        thread {
            try {
                val endpoint = URL("$requestedProvider/api/search?q=${enc(query)}&city=${enc("Тверь")}&sources=${enc(selected.joinToString(","))}")
                val connection = endpoint.openConnection() as HttpURLConnection
                connection.connectTimeout = 15000; connection.readTimeout = 30000
                try {
                    val code = connection.responseCode
                    if (code !in 200..299) throw IllegalStateException("HTTP $code")
                    val root = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val arr = root.optJSONArray("items")
                    val parsed = mutableListOf<Offer>()
                    if (arr != null) for (i in 0 until arr.length()) {
                        val item = arr.optJSONObject(i) ?: continue
                        val link = item.optString("url")
                        if (!validCard(link)) continue
                        val amount = if (item.isNull("price") || !item.has("price")) null else item.optDouble("price").takeIf { !it.isNaN() && it >= 0.0 }
                        parsed.add(Offer(item.optString("title", "Конструктор"), item.optString("shop", "Магазин"), link,
                            amount, item.optString("image").takeIf { it.startsWith("https://") }))
                    }
                    runOnUiThread {
                        sortedOffers = parsed.distinctBy { it.link }
                        statusText.text = if (parsed.isEmpty()) "Подтверждённых совпадений нет. Попробуй другой артикул." else "Найденные цены ориентировочные. Наличие и доставку в Тверь проверь в магазине."
                        refreshOffers()
                    }
                } finally { connection.disconnect() }
            } catch (e: Exception) { runOnUiThread {
                offersPanel.removeAllViews()
                statusText.text = "Не удалось получить предложения: ${e.localizedMessage ?: "ошибка соединения"}"
            } }
        }
    }
    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
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
    private fun refreshOffers() {
        if (!::offersPanel.isInitialized) return
        offersPanel.removeAllViews()
        val favs = saved()
        val filtered = sortedOffers.filter { !favoritesOnly || favs.contains(it.link) }
            .sortedWith(if (ascending) Comparator<Offer> { a, b -> (a.price ?: Double.POSITIVE_INFINITY).compareTo(b.price ?: Double.POSITIVE_INFINITY) } else Comparator<Offer> { a, b -> (b.price ?: Double.NEGATIVE_INFINITY).compareTo(a.price ?: Double.NEGATIVE_INFINITY) })
        countText.text = if (favoritesOnly) "Избранное (${filtered.size})" else "Предложения (${filtered.size})"
        if (filtered.isEmpty()) {
            offersPanel.addView(text(if (favoritesOnly) "Пока нет избранных предложений из текущего поиска." else "Подходящих предложений пока нет.", 14f, muted))
            return
        }
        filtered.forEach { offersPanel.addView(offerCard(it, favs.contains(it.link)), margin(bottom = 10)) }
    }
    private fun offerCard(offer: Offer, fav: Boolean): View {
        val container = column().apply { background = rounded(surface, 17, Color.rgb(47, 58, 76)); setPadding(dp(12), dp(12), dp(12), dp(12)) }
        val line = row().apply { gravity = Gravity.TOP }
        val image = ImageView(this).apply {
            background = rounded(surface2, 10)
            scaleType = ImageView.ScaleType.CENTER_CROP
            setImageResource(android.R.drawable.ic_menu_gallery)
        }
        line.addView(image, LinearLayout.LayoutParams(dp(112), dp(112)))
        loadOfferImage(offer.image, image)
        val info = column().apply { setPadding(dp(12), dp(2), 0, 0) }
        val heading = text(offer.title, 14f, white, true).apply { maxLines = 3; ellipsize = android.text.TextUtils.TruncateAt.END }
        info.addView(heading)
        info.addView(text(offer.shop, 12f, yellow, true), margin(top = 7))
        info.addView(text(offer.price?.let { "${money.format(it)} ₽" } ?: "Цена неизвестна", 21f, white, true), margin(top = 8))
        line.addView(info, LinearLayout.LayoutParams(0, -2, 1f))
        container.addView(line)
        val actions = row()
        val open = button("Открыть товар ↗", yellow, bg).apply { textSize = 14f; setOnClickListener { openOffer(offer.link) } }
        actions.addView(open, LinearLayout.LayoutParams(0, dp(47), 1f))
        val heart = text(if (fav) "♥" else "♡", 28f, if (fav) yellow else white).apply {
            gravity = Gravity.CENTER; setOnClickListener { toggleSaved(offer.link) }; contentDescription = "Добавить в избранное"
        }
        actions.addView(heart, LinearLayout.LayoutParams(dp(54), dp(47)))
        container.addView(actions, margin(top = 12))
        return container
    }
    private fun loadOfferImage(imageUrl: String?, view: ImageView) {
        if (imageUrl == null) return
        thread {
            try {
                val connection = URL(imageUrl).openConnection() as HttpURLConnection
                connection.connectTimeout = 7000; connection.readTimeout = 9000
                connection.instanceFollowRedirects = true
                try {
                    if (connection.responseCode !in 200..299) return@thread
                    val bitmap: Bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) } ?: return@thread
                    runOnUiThread { if (view.parent != null) view.setImageBitmap(bitmap) }
                } finally { connection.disconnect() }
            } catch (_: Exception) { /* Image is optional, never disrupt product offers */ }
        }
    }
    private fun openOffer(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { statusText.text = "Не удалось открыть карточку товара." }
    }
    override fun onDestroy() { recognizer.close(); super.onDestroy() }
    companion object { private const val DEFAULT_PROVIDER = "https://brickfinder-api.lehalepeha8658.workers.dev" }
}
