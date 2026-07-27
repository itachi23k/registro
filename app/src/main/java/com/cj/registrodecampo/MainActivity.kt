    package com.cj.registrodecampo

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.*
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.VibrationEffect
import android.os.Vibrator
import android.text.InputType
import android.util.Log
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.common.api.ResolvableApiException
import com.google.android.gms.location.*
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseApp
import com.google.firebase.appcheck.playintegrity.PlayIntegrityAppCheckProviderFactory
import com.google.firebase.appcheck.appCheck
import com.google.firebase.Firebase
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : AppCompatActivity() {

    private var servicoAtual = "ROÇADA"
    private var estacaAtual = 4032
    private var ultimaLocalizacaoUTM = ""
    private var latAtualBruta: Double = 0.0
    private var lonAtualBruta: Double = 0.0

    private val opcoesLado = arrayOf("D", "E", "D/E", "EIXO")
    private var indexLado = 0
    private var ladoAtual = opcoesLado[indexLado]
    private val opcoesContrato = arrayOf("TT-563/2024", "SR-592/2020", "SR-593/2020")
    private var indexContrato = 0
    private var contratoAtual = opcoesContrato[indexContrato]

    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var vibrator: Vibrator
    private lateinit var locationCallback: LocationCallback
    private lateinit var ultimaFotoUri: Uri

    private var hardwareIniciado = false

    // Monitor de GPS em tempo real
    private val gpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == LocationManager.PROVIDERS_CHANGED_ACTION) {
                verificarGpsEAlertar()
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)



        // Inicializa Segurança
        configurarAppCheck()

        configurarTelaCheia()

        if (android.os.Build.VERSION.SDK_INT >= 35) {
            window.attributes.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        }

        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        cameraExecutor = Executors.newSingleThreadExecutor()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                for (location in locationResult.locations) {
                    latAtualBruta = location.latitude
                    lonAtualBruta = location.longitude
                    converterParaUTMReal(location.latitude, location.longitude)
                    atualizarVisorHUD()
                }
            }
        }

        carregarProgresso()
        configurarBotoes()
        pedirPermissoes()
    }
    private fun ajustarTamanhoHud() {
        val cardCamera = findViewById<androidx.cardview.widget.CardView>(R.id.cardCamera)

        // O post garante que o código execute depois que o Android mediu o tamanho da tela
        cardCamera.post {
            val alturaCard = cardCamera.height
            val tamanhoTexto = alturaCard * 0.04f // 4% da altura

            val ids = arrayOf(
                R.id.hudTvUtm, R.id.hudTvRodovia, R.id.hudTvContrato,
                R.id.hudTvEstaca, R.id.hudTvLado, R.id.hudTvServico, R.id.hudTvData
            )

            for (id in ids) {
                findViewById<TextView>(id)?.let { tv ->
                    // Usamos COMPLEX_UNIT_PX porque o cálculo já foi feito em pixels
                    tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, tamanhoTexto)

                    // Dica extra: sombra para garantir leitura em fundos claros
                    tv.setShadowLayer(3f, 2f, 2f, Color.BLACK)
                }
            }
        }
    }
    private fun configurarAppCheck() {
        try {
            FirebaseApp.initializeApp(this)
            Firebase.appCheck.installAppCheckProviderFactory(
                PlayIntegrityAppCheckProviderFactory.getInstance()
            )
        } catch (e: Exception) {
            Log.e("APP_CHECK", "Erro: ${e.message}")
        }
    }

    override fun onResume() {
        super.onResume()
        verificarGpsEAlertar()
        registerReceiver(gpsReceiver, IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION))

        val cameraPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
        val gpsPerm = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)

        if (cameraPerm == PackageManager.PERMISSION_GRANTED && gpsPerm == PackageManager.PERMISSION_GRANTED) {
            if (!hardwareIniciado) {
                iniciarProcessosDeHardware()
            }
        }
    }

    override fun onPause() {
        super.onPause()
        try { unregisterReceiver(gpsReceiver) } catch (e: Exception) {}
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configurarTelaCheia()
        }
    }

    private fun iniciarProcessosDeHardware() {
        if (hardwareIniciado) return
        hardwareIniciado = true

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                loc?.let {
                    converterParaUTMReal(it.latitude, it.longitude)
                    atualizarVisorHUD()
                }
            }
            startLocationUpdates()
        }

        findViewById<View>(R.id.viewFinder).postDelayed({
            startCamera()
        }, 800)
    }

    private fun configurarTelaCheia() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            val controller = window.insetsController
            if (controller != null) {
                controller.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
                controller.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN)
        }
    }

    private fun configurarBotoes() {
        findViewById<MaterialButton>(R.id.btnService).setOnClickListener { view ->
            val popup = PopupMenu(this, view)
            val lista = arrayOf(
                                    "APLICAÇÃO DE BGS",
                                    "APLICAÇÃO DE CBUQ",
                                    "CAMADA DE MACADAME SECO",
                                    "CAMADA DE REFORÇO",
                                    "CANTEIRO DE OBRAS",
                                    "CHECKLIST DE EQUIPAMENTO",
                                    "CORREÇÃO DE ENROCAMENTO",
                                    "DESCARREGAMENTO DE BALSA",
                                    "ENROCAMENTO",
                                    "EROSÃO",
                                    "ESTOQUE DE MATERIAL",
                                    "EXECUÇÃO DE BUEIRO",
                                    "EXECUÇÃO DE PONTILHÃO",
                                    "FAIXA DE DOMÍNIO",
                                    "HIDROSSEMEADURA",
                                    "IMPLANTAÇÃO DE PLACAS",
                                    "IMPRIMAÇÃO",
                                    "LANÇAMENTO DE BGS",
                                    "LASTRO DE BRITA",
                                    "LASTRO DE AREIA",
                                    "LIMPEZA DE SARJETA",
                                    "LIMPEZA DE VALA DE DRENAGEM",
                                    "PATRULHA DE EQUIPAMENTOS",
                                    "PINTURA DE LIGAÇÃO",
                                    "PLANTIO DE GRAMA",
                                    "PONTE DE MADEIRA",
                                    "RECOMPOSIÇÃO MECANIZADA DE ATERRO",
                                    "REFORMA DE PONTE",
                                    "REGULARIZAÇÃO DE SUBLEITO",
                                    "REMENDO PROFUNDO",
                                    "REMOÇÃO E TRANSPORTE",
                                    "REMOÇÃO DE ÁRVORES",
                                    "REVESTIMENTO PRIMÁRIO 01",
                                    "REVESTIMENTO PRIMÁRIO 02",
                                    "ROÇADA",
                                    "SINALIZAÇÃO TEMPORÁRIA",
                                    "SINALIZAÇÃO VERTICAL",
                                    "TAPA BURACO",
                                    "TEMPO CHUVOSO",
                                    "VALA DE DRENAGEM")
            lista.forEach { popup.menu.add(it) }
            popup.setOnMenuItemClickListener { item ->
                servicoAtual = item.title.toString()
                salvarProgresso(); atualizarVisorHUD(); true
            }
            popup.show()
        }

        findViewById<MaterialButton>(R.id.btnSideContrato).setOnClickListener {
            indexContrato = (indexContrato + 1) % opcoesContrato.size
            contratoAtual = opcoesContrato[indexContrato]
            vibrar(70)
            salvarProgresso(); atualizarVisorHUD()
        }

        findViewById<MaterialButton>(R.id.btnSideLado).setOnClickListener {
            indexLado = (indexLado + 1) % opcoesLado.size
            ladoAtual = opcoesLado[indexLado]
            vibrar(70)
            salvarProgresso(); atualizarVisorHUD()
        }

        findViewById<MaterialButton>(R.id.btnEstacaPlus).setOnClickListener {
            estacaAtual++; salvarProgresso(); atualizarVisorHUD()
        }

        findViewById<MaterialButton>(R.id.btnEstacaMinus).setOnClickListener {
            if (estacaAtual > 0) estacaAtual--
            salvarProgresso(); atualizarVisorHUD()
        }

        findViewById<TextView>(R.id.tvEstacaDisplay).setOnClickListener { abrirDialogoEstaca() }

        findViewById<ImageButton>(R.id.btnInjectEstaca).setOnClickListener {
            if (latAtualBruta != 0.0 && lonAtualBruta != 0.0) {
                val calculadora = CalculadoraObra(this)
                val resultado = calculadora.localizarMaisProximo(latAtualBruta, lonAtualBruta)
                if (resultado != null) {
                    estacaAtual = resultado.estaca
                    contratoAtual = resultado.contrato
                    runOnUiThread {
                        atualizarVisorHUD()
                        salvarProgresso()
                        Toast.makeText(this, "GPS: Estaca $estacaAtual", Toast.LENGTH_SHORT).show()
                    }
                }
            } else {
                Toast.makeText(this, "Aguardando GPS...", Toast.LENGTH_SHORT).show()
            }
        }

        findViewById<ImageButton>(R.id.btnShutter).setOnClickListener {
            it.animate().scaleX(0.8f).scaleY(0.8f).setDuration(100).withEndAction {
                it.animate().scaleX(1f).scaleY(1f).setDuration(100).start()
            }.start()
            vibrar(70)
            takePhoto()
        }

        findViewById<View>(R.id.btnGalleryThumbnail).setOnClickListener {
            if (::ultimaFotoUri.isInitialized) {
                val intent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(ultimaFotoUri, "image/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                startActivity(intent)
            }
        }
    }

    private fun takePhoto() {
        val imgCap = imageCapture ?: return
        if (latAtualBruta == 0.0) {
            Toast.makeText(this, "⚠️ Aguardando GPS...", Toast.LENGTH_SHORT).show()
            return
        }

        imgCap.takePicture(ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                cameraExecutor.execute {
                    try {
                        val buffer = image.planes[0].buffer
                        val bytes = ByteArray(buffer.remaining())
                        buffer.get(bytes)
                        val bitmapOriginal = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                        image.close()

                        val timeStamp = SimpleDateFormat("ddMMyy_HHmmss", Locale.getDefault()).format(Date())
                        val contratoParaArquivo = contratoAtual.replace("/", "-")

                        val bitmapClean = Bitmap.createScaledBitmap(bitmapOriginal, 1440, 1080, true)
                        //bitmapOriginal.recycle()

                        val bitmapLegend = bitmapClean.copy(bitmapClean.config ?: Bitmap.Config.ARGB_8888, true)
                        estamparDadosNoBitmap(bitmapLegend)

                        val nomeClean = "IMG_${contratoParaArquivo}_EST${estacaAtual}_CLEAN_$timeStamp.jpg"
                        val nomeLegend = "IMG_${contratoParaArquivo}_EST${estacaAtual}_LEGEND_$timeStamp.jpg"

                        salvarNoDiscoEDevolverUri(bitmapClean, nomeClean)
                        val uriFinal = salvarNoDiscoEDevolverUri(bitmapLegend, nomeLegend)

                        runOnUiThread {
                            uriFinal?.let {
                                ultimaFotoUri = it
                                findViewById<ImageView>(R.id.btnGalleryThumbnail).setImageURI(it)
                                Toast.makeText(baseContext, "✅ Registrado!", Toast.LENGTH_SHORT).show()
                            }
                        }
                    } catch (e: Exception) {
                        runOnUiThread { Toast.makeText(baseContext, "Erro: ${e.message}", Toast.LENGTH_LONG).show() }
                    }
                }
            }
            override fun onError(e: ImageCaptureException) {
                runOnUiThread { Toast.makeText(baseContext, "Erro Hardware: ${e.message}", Toast.LENGTH_LONG).show() }
            }
        })
    }

    private fun normalizarParaExif(texto: String): String {
        val temp = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
        val semAcentos = "\\p{InCombiningDiacriticalMarks}+".toRegex().replace(temp, "")
        return semAcentos.replace("Ç", "C").replace("ç", "c").uppercase()
    }

        private fun salvarNoDiscoEDevolverUri(bitmap: Bitmap, nome: String): Uri? {
            return try {
                // Tratamento do contrato (já estava aqui)
                val pastaContrato = contratoAtual.replace("/", "-")

                // Tratamento do lado: Troca a barra por traço para evitar subpastas indesejadas
                val ladoLimpo = ladoAtual.replace("/", "-")

                // Caminho atualizado com o sufixo do lado na pasta da estaca
                val caminhoRelativo = "Pictures/Medicoes_BR319/$pastaContrato/$servicoAtual/Estaca_${estacaAtual}_$ladoLimpo"

                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, nome)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, caminhoRelativo)
                    put(android.provider.MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val uri = contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: throw Exception("Erro MediaStore")

                contentResolver.openOutputStream(uri)?.use { os ->
                    bitmap.compress(Bitmap.CompressFormat.JPEG, 95, os)
                    os.flush()
                }

                contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                    val exif = ExifInterface(pfd.fileDescriptor)
                    exif.setLatLong(latAtualBruta, lonAtualBruta)

                    val sdfData = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
                    val sdfHora = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
                    val dna = "${normalizarParaExif(contratoAtual)}|${normalizarParaExif(servicoAtual)}|$estacaAtual|$ladoAtual|$ultimaLocalizacaoUTM|$sdfData|$sdfHora"

                    exif.setAttribute(ExifInterface.TAG_USER_COMMENT, dna)
                    exif.setAttribute(ExifInterface.TAG_DATETIME, SimpleDateFormat("yyyy:MM:dd HH:mm:ss", Locale.getDefault()).format(Date()))
                    exif.saveAttributes()
                    pfd.fileDescriptor.sync()
                }

                values.clear()
                values.put(android.provider.MediaStore.MediaColumns.IS_PENDING, 0)
                contentResolver.update(uri, values, null, null)
                uri
            } catch (e: Exception) {
                salvarViaEmergencia(bitmap, nome)
            }
        }

    private fun salvarViaEmergencia(bitmap: Bitmap, nome: String): Uri? {
        return try {
            val pastaContrato = contratoAtual.replace("/", "-")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Medicoes_BR319/$pastaContrato/$servicoAtual/Estaca_$estacaAtual")
            if (!dir.exists()) dir.mkdirs()
            val file = File(dir, nome)
            FileOutputStream(file).use { fos ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, fos)
                fos.flush()
                fos.fd.sync()
            }
            android.media.MediaScannerConnection.scanFile(this, arrayOf(file.absolutePath), null, null)
            Uri.fromFile(file)
        } catch (e: Exception) { null }
    }

    private fun estamparDadosNoBitmap(bitmap: Bitmap) {
        val canvas = Canvas(bitmap)
        val w = bitmap.width
        val h = bitmap.height

        try {
            val logo = BitmapFactory.decodeResource(resources, R.drawable.logo_lcm)
            if (logo != null) {
                val wLogo = (w * 0.20f).toInt()
                val hLogo = (logo.height * wLogo) / logo.width
                val logoScaled = Bitmap.createScaledBitmap(logo, wLogo, hLogo, true)
                canvas.drawBitmap(logoScaled, w - wLogo - 40f, 40f, null)
                logoScaled.recycle()
            }
        } catch (e: Exception) {}

        val paint = Paint().apply {
            color = Color.WHITE
            textSize = h * 0.038f
            typeface = ResourcesCompat.getFont(this@MainActivity, R.font.araboto_normal) ?: Typeface.DEFAULT_BOLD
            isAntiAlias = true
            setShadowLayer(3f, 3f, 3f, Color.BLACK)
            textAlign = Paint.Align.RIGHT
        }

        val linhas = mutableListOf<String>()
        if (ultimaLocalizacaoUTM.isNotEmpty()) linhas.add(ultimaLocalizacaoUTM)
        linhas.add("BR-319/AM")
        linhas.add(contratoAtual)
        linhas.add("ESTACA: $estacaAtual")
        linhas.add("LADO: $ladoAtual")
        linhas.add("SERVIÇO: $servicoAtual")
        linhas.add(SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date()))

        var yCoord = h * 0.95f
        for (linha in linhas.reversed()) {
            canvas.drawText(linha, w * 0.97f, yCoord, paint)
            yCoord -= (h * 0.045f)
        }
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            try {
                val cameraProvider = cameraProviderFuture.get()
                cameraProvider.unbindAll()
                val preview = Preview.Builder().setTargetAspectRatio(AspectRatio.RATIO_4_3).build()
                    .also { it.setSurfaceProvider(findViewById<androidx.camera.view.PreviewView>(R.id.viewFinder).surfaceProvider) }
                imageCapture = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageCapture)
            } catch (exc: Exception) {
                findViewById<View>(R.id.viewFinder).postDelayed({ startCamera() }, 1000)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun startLocationUpdates() {
        val locationRequest = LocationRequest.create().apply {
            interval = 2000
            fastestInterval = 1000
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
                fusedLocationClient.requestLocationUpdates(locationRequest, locationCallback, android.os.Looper.getMainLooper())
            }
        } catch (e: Exception) { Log.e("UTM_ERROR", "Erro GPS: ${e.message}") }
    }

    private fun converterParaUTMReal(lat: Double, lon: Double): String {
        val a = 6378137.0; val f = 1 / 298.257223563; val k0 = 0.9996
        val zone = ((lon + 180) / 6).toInt() + 1
        val lonCentral = (zone * 6 - 183).toDouble()
        val latRad = Math.toRadians(lat); val lonRad = Math.toRadians(lon); val lonCentralRad = Math.toRadians(lonCentral)
        val e2 = 2 * f - f * f
        val n_val = a / Math.sqrt(1 - e2 * Math.sin(latRad) * Math.sin(latRad))
        val t = Math.tan(latRad) * Math.tan(latRad); val a_val = Math.cos(latRad) * (lonRad - lonCentralRad)
        val m = a * ((1 - e2 / 4 - 3 * e2 * e2 / 64) * latRad - (3 * e2 / 8 + 3 * e2 * e2 / 32) * Math.sin(2 * latRad) + (15 * e2 * e2 / 256) * Math.sin(4 * latRad))
        val east = k0 * n_val * (a_val + (1 - t) * a_val * a_val * a_val / 6) + 500000.0
        var north = k0 * (m + n_val * Math.tan(latRad) * (a_val * a_val / 2))
        if (lat < 0) north += 10000000.0

        ultimaLocalizacaoUTM = "${zone}${if (lat > -8) "M" else "L"} ${String.format(Locale.US, "%.0f", east.toDouble())} ${String.format(Locale.US, "%.0f", north.toDouble())}"
        return ultimaLocalizacaoUTM
    }

    private fun pedirPermissoes() {
        val p = arrayOf(Manifest.permission.CAMERA, Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        if (p.any { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }) {
            ActivityCompat.requestPermissions(this, p, 10)
        } else {
            findViewById<View>(R.id.viewFinder).post { startCamera(); startLocationUpdates() }
        }
    }

    private fun atualizarVisorHUD() {
        runOnUiThread {
            findViewById<TextView>(R.id.hudTvData)?.text = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault()).format(Date())
            findViewById<TextView>(R.id.tvEstacaDisplay)?.text = estacaAtual.toString()
            findViewById<TextView>(R.id.hudTvUtm)?.text = ultimaLocalizacaoUTM
            findViewById<TextView>(R.id.hudTvContrato)?.text = contratoAtual
            findViewById<TextView>(R.id.hudTvEstaca)?.text = "ESTACA: $estacaAtual"
            findViewById<TextView>(R.id.hudTvLado)?.text = "LADO: $ladoAtual"
            findViewById<TextView>(R.id.hudTvServico)?.text = "SERVIÇO: $servicoAtual"
            //findViewById<MaterialButton>(R.id.btnSideLado)?.text = "LADO: $ladoAtual"
            //findViewById<MaterialButton>(R.id.btnSideContrato)?.text = contratoAtual
        }
    }

    private fun vibrar(ms: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        } else { vibrator.vibrate(ms) }
    }

    private fun abrirDialogoEstaca() {
        val input = EditText(this).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            setText(estacaAtual.toString())
        }
        AlertDialog.Builder(this).setTitle("Estaca").setView(input)
            .setPositiveButton("OK") { _, _ ->
                input.text.toString().toIntOrNull()?.let { estacaAtual = it; salvarProgresso(); atualizarVisorHUD() }
            }.show()
    }

    private fun salvarProgresso() {
        getSharedPreferences("DadosObra", MODE_PRIVATE).edit().apply {
            putInt("SAVED_ESTACA", estacaAtual)
            putString("SAVED_SERVICO", servicoAtual)

            // Salvamos o valor String e o Índice para garantir consistência
            putString("SAVED_CONTRATO", contratoAtual)
            putInt("SAVED_INDEX_CONTRATO", indexContrato)

            // A NOVIDADE: Persistência do Lado
            putString("SAVED_LADO", ladoAtual)
            putInt("SAVED_INDEX_LADO", indexLado)

            apply()
        }
    }

    private fun carregarProgresso() {
        val sp = getSharedPreferences("DadosObra", MODE_PRIVATE)

        // Recupera Estaca e Serviço
        estacaAtual = sp.getInt("SAVED_ESTACA", 4032)
        servicoAtual = sp.getString("SAVED_SERVICO", "ROÇADA") ?: "ROÇADA"

        // Recupera Contrato e seu Índice
        indexContrato = sp.getInt("SAVED_INDEX_CONTRATO", 0)
        contratoAtual = sp.getString("SAVED_CONTRATO", opcoesContrato[indexContrato]) ?: opcoesContrato[indexContrato]

        // Recupera Lado e seu Índice
        indexLado = sp.getInt("SAVED_INDEX_LADO", 0)
        ladoAtual = sp.getString("SAVED_LADO", opcoesLado[indexLado]) ?: opcoesLado[indexLado]

        atualizarVisorHUD()
    }

    private fun verificarGpsEAlertar() {
        val builder = LocationSettingsRequest.Builder().addLocationRequest(LocationRequest.create().setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY))
        LocationServices.getSettingsClient(this).checkLocationSettings(builder.build()).addOnFailureListener { e ->
            if (e is ResolvableApiException) try { e.startResolutionForResult(this, 1001) } catch (i: Exception) {}
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        cameraExecutor.shutdown()
        try { fusedLocationClient.removeLocationUpdates(locationCallback) } catch (e: Exception) {}
    }
}