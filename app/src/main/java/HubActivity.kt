package com.cj.registrodecampo

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.google.firebase.firestore.FirebaseFirestore
import java.io.File
import java.util.*

class HubActivity : AppCompatActivity() {

    private lateinit var tvContador: TextView
    private lateinit var prefsLicenca: SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Configuração de Tela Cheia (Edge-to-Edge para Android 14/15)
        configurarTelaCheia()

        setContentView(R.layout.activity_hub)

        prefsLicenca = getSharedPreferences("LicencaPrefs", MODE_PRIVATE)
        tvContador = findViewById(R.id.tvContadorPendentes)

        // Botão Câmera
        findViewById<View>(R.id.cardCamera).setOnClickListener {
            startActivity(Intent(this, MainActivity::class.java))
        }

        // Botão Sincronização
        findViewById<View>(R.id.cardSync).setOnClickListener {
            startActivity(Intent(this, SyncActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        // Reaplica tela cheia ao voltar para o app
        configurarTelaCheia()

        // Validação de Segurança e Licença
        verificarLicencaHibrida()

        // Atualização do contador de fotos pendentes
        atualizarContador()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            configurarTelaCheia()
        }
    }

    private fun configurarTelaCheia() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    private fun verificarLicencaHibrida() {
        val dataExpiracaoLocal = prefsLicenca.getLong("data_expiracao", 0)
        val ultimoCheckin = prefsLicenca.getLong("ultimo_checkin_online", 0)
        val agora = System.currentTimeMillis()

        // --- TESTE 1: INTEGRIDADE (Relógio atrasado) ---
        if (agora < ultimoCheckin) {
            bloquearApp("Data do sistema inconsistente! Ajuste seu relógio.")
            return
        }

        // --- TESTE 2: CONSULTA ONLINE ---
        if (isOnline()) {
            consultarFirestoreSilencioso()
        } else {
            // --- TESTE 3: LIMITE OFFLINE (30 Dias) ---
            val trintaDiasEmMs = 30L * 24 * 60 * 60 * 1000
            if (agora - ultimoCheckin > trintaDiasEmMs && ultimoCheckin != 0L) {
                bloquearApp("Necessário conectar à internet (Limite de 30 dias offline).")
                return
            }
        }

        // --- TESTE 4: EXPIRAÇÃO FINAL ---
        if (dataExpiracaoLocal != 0L && agora > dataExpiracaoLocal) {
            bloquearApp("Sua licença expirou!")
        }
    }

    private fun consultarFirestoreSilencioso() {
        // PEGADA DE SEGURANÇA: Se o ID estiver vazio, não consultamos para evitar crash (Segments error)
        val idLicenca = prefsLicenca.getString("id_licenca", "")
        if (idLicenca.isNullOrEmpty()) {
            Log.w("LICENCA", "Aguardando ativação inicial: ID não encontrado.")
            return
        }

        val db = FirebaseFirestore.getInstance()
        db.collection("licencas").document(idLicenca).get()
            .addOnSuccessListener { doc ->
                if (doc.exists()) {
                    val status = doc.getString("status")?.uppercase() ?: "INATIVO"
                    val expMs = doc.getLong("data_expiracao") ?: 0

                    if (status == "APROVADO") {
                        prefsLicenca.edit().apply {
                            putLong("data_expiracao", expMs)
                            putLong("ultimo_checkin_online", System.currentTimeMillis())
                            apply()
                        }
                    } else {
                        bloquearApp("Status da licença: $status")
                    }
                }
            }
            .addOnFailureListener { e ->
                Log.e("LICENCA", "Falha silenciosa na consulta: ${e.message}")
            }
    }

    private fun bloquearApp(mensagem: String) {
        Toast.makeText(this, mensagem, Toast.LENGTH_LONG).show()
        val intent = Intent(this, AtivacaoActivity::class.java)
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
        startActivity(intent)
        finish()
    }

    private fun isOnline(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false

        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private fun atualizarContador() {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val pastaRaiz = File(root, "Medicoes_BR319")
        val arquivoLog = File(getExternalFilesDir(null), "registro_sync.txt")

        if (!arquivoLog.exists()) arquivoLog.createNewFile()
        val nomesEnviados = arquivoLog.readLines().map { it.split("|")[0] }

        var pendentes = 0
        if (pastaRaiz.exists()) {
            pastaRaiz.walkTopDown().forEach { file ->
                if (file.isFile && file.extension == "jpg" && !nomesEnviados.contains(file.name)) {
                    pendentes++
                }
            }
        }

        tvContador.text = pendentes.toString()
        tvContador.visibility = if (pendentes > 0) View.VISIBLE else View.GONE
    }
}