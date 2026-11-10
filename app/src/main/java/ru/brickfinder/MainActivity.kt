package ru.brickfinder.app

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.graphics.Color
import android.view.ViewGroup
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

class MainActivity : Activity() {
    private lateinit var searchText: EditText
    private lateinit var statusText: TextView
    private lateinit var results: LinearLayout
    private lateinit var apiUrl: EditText
    private var imageSelected = false
    private val recognizer by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val pickerCode = 151

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val scroll = ScrollView(this)
        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(28,36,28,28) }
        scroll.addView(content)
        setContentView(scroll)
        content.addView(TextView(this).apply { text="BrickFinder"; textSize=29f; setTextColor(Color.rgb(15,38,85)) })
        content.addView(TextView(this).apply { text="Конструкторы LEGO и аналоги · доставка в Тверь"; textSize=15f })
        content.addView(TextView(this).apply { text="Поиск по фото: фотография важнее введённого текста. Распознавание артикула выполняется на телефоне бесплатно."; textSize=14f; setPadding(0,22,0,14) })
        searchText = field("Название или артикул, например 42143",content)
        val photo = Button(this).apply { text="Выбрать фотографию"; setOnClickListener { pickPhoto() } }
        content.addView(photo)
        content.addView(Button(this).apply { text="Найти предложения"; setOnClickListener { lookup(searchText.text.toString().trim()) } })
        apiUrl = field("HTTPS-адрес сервера (для реальных цен)",content)
        apiUrl.setText(getPreferences(MODE_PRIVATE).getString("api", ""))
        content.addView(Button(this).apply { text="Сохранить адрес источника цен"; setOnClickListener {
            val u=apiUrl.text.toString().trim().trimEnd('/')
            if (u.isNotBlank() && !(u.startsWith("https://"))) { inform("Требуется HTTPS-адрес"); return@setOnClickListener }
            getPreferences(MODE_PRIVATE).edit().putString("api",u).apply(); inform("Адрес сохранён")
        } })
        statusText=TextView(this).apply { text="Город: Тверь. Источники цен не подключены."; setPadding(0,18,0,18) }
        content.addView(statusText)
        results=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        content.addView(results)
    }
    private fun field(hint:String,root: LinearLayout): EditText = EditText(this).also {
        it.hint=hint; it.setSingleLine(true); root.addView(it,LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,ViewGroup.LayoutParams.WRAP_CONTENT))
    }
    private fun inform(s:String) { statusText.text=s }
    private fun pickPhoto() {
        val i=Intent(Intent.ACTION_GET_CONTENT).apply { type="image/*"; addCategory(Intent.CATEGORY_OPENABLE) }
        startActivityForResult(Intent.createChooser(i,"Выберите фото конструктора"),pickerCode)
    }
    @Deprecated("Used for compatibility with API 23+")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if (requestCode!=pickerCode || resultCode!=RESULT_OK) return
        val uri:Uri=data?.data ?: return
        imageSelected=true
        inform("Распознаю артикул на фото…")
        try {
            recognizer.process(InputImage.fromFilePath(this,uri))
                .addOnSuccessListener { txt ->
                    val raw=txt.text
                    val sku=Regex("(?<!\\d)\\d{4,7}(?!\\d)").find(raw)?.value
                    val candidate=sku ?: raw.lineSequence().map { it.trim() }.firstOrNull { it.length in 4..65 }.orEmpty()
                    if(candidate.isBlank()) {
                        inform("Не удалось прочитать название или номер. Попробуй фото коробки крупнее либо введи артикул.")
                    } else {
                        searchText.setText(candidate)
                        inform("Фото распознано: $candidate. Ищу предложения…")
                        lookup(candidate)
                    }
                }.addOnFailureListener { inform("Не удалось обработать изображение. Можно ввести артикул вручную.") }
        } catch(e:Exception) { inform("Не удалось открыть фото: ${e.localizedMessage}") }
    }
    private fun lookup(q:String) {
        results.removeAllViews()
        if (q.length<2) { inform("Укажи набор или загрузи фото коробки"); return }
        val base=getPreferences(MODE_PRIVATE).getString("api", "")?.trim()?.trimEnd('/').orEmpty()
        if (!base.startsWith("https://")) {
            inform("Набор определён: $q. Реальных карточек и цен пока нет: бесплатный источник предложений не подключён. Поисковые ссылки вместо карточек не подставляются.")
            return
        }
        inform("Запрашиваю прямые карточки товаров…")
        thread {
            try {
                val url=URL("$base/api/search?q=${URLEncoder.encode(q,"UTF-8")}&city=${URLEncoder.encode("Тверь","UTF-8")}&analog_first=true")
                val connection=url.openConnection() as HttpURLConnection
                connection.connectTimeout=12000; connection.readTimeout=25000
                try {
                    if(connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
                    val body=connection.inputStream.bufferedReader().use { it.readText() }
                    val obj=JSONObject(body); val items=obj.optJSONArray("items")
                    runOnUiThread {
                        if(items==null || items.length()==0) { inform("Проверенных товарных предложений не найдено. Цены не выдумываются."); return@runOnUiThread }
                        inform("Найдено предложений: ${items.length()}. Цены и доставку проверь в магазине.")
                        for(i in 0 until items.length()) {
                            val it=items.getJSONObject(i)
                            val link=it.optString("url")
                            val shop=it.optString("shop")
                            val title=it.optString("title")
                            val price=if(it.isNull("price")) "Цена неизвестна" else "${NumberFormat.getNumberInstance(Locale("ru","RU")).format(it.optDouble("price"))} ₽"
                            if(!link.startsWith("https://"))continue
                            val card=Button(this).apply {
                                text="$shop · $price\n$title\nОткрыть карточку товара"
                                isAllCaps=false
                                setOnClickListener { startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(link))) }
                            }
                            results.addView(card)
                        }
                    }
                } finally { connection.disconnect() }
            } catch(e:Exception) {
                runOnUiThread { inform("Не удалось получить подтверждённые предложения: ${e.message}. Проверь HTTPS-адрес и сервер.") }
            }
        }
    }
    override fun onDestroy() { super.onDestroy(); recognizer.close() }
}
