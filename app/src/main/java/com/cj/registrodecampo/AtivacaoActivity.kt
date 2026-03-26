package com.cj.registrodecampo

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.MemoryCacheSettings
import com.google.firebase.firestore.PersistentCacheSettings
import com.google.firebase.firestore.Source

class AtivacaoActivity : AppCompatActivity() {

    private lateinit var deviceId: String

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 1. Configuração do Firestore (Otimizado para Offline)
        val settings = FirebaseFirestoreSettings.Builder()
            .setLocalCacheSettings(MemoryCacheSettings.newBuilder().build())
            .setLocalCacheSettings(PersistentCacheSettings.newBuilder().build())
            .build()
        FirebaseFirestore.getInstance().firestoreSettings = settings

        deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID).uppercase()

        // 2. Fluxo de Entrada: Se já estiver ativado, tenta validar silenciosamente e entra
        if (estaAtivadoValido()) {
            checagemSilenciosaLicenca()
            irParaHub()
            return
        }

        // Se não estiver ativado, mostra a tela de ativação via nuvem
        setContentView(R.layout.activity_ativacao)

        // Nota: O XML deve conter apenas tvDeviceId e btnAtivarFirebase agora
        val btnAtivarFirebase = findViewById<MaterialButton>(R.id.btnAtivarFirebase)
        val tvDeviceId = findViewById<TextView>(R.id.tvDeviceId)

        tvDeviceId.text = "ID DO APARELHO: $deviceId"

        tvDeviceId.setOnLongClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = android.content.ClipData.newPlainText("DeviceID", deviceId)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "ID Copiado!", Toast.LENGTH_SHORT).show()
            true
        }

        btnAtivarFirebase.setOnClickListener {
            verificarLicencaNuvem(deviceId)
        }
    }

    private fun verificarLicencaNuvem(id: String) {
        Toast.makeText(this, "Validando licença...", Toast.LENGTH_SHORT).show()
        val db = FirebaseFirestore.getInstance()

        // Busca obrigatória no SERVIDOR para garantir ativação real
        db.collection("licencas").document(id).get(Source.SERVER)
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val status = document.getString("status") ?: "BLOQUEADO"
                    val nomeFiscal = document.getString("nome") ?: "Fiscal"

                    val dataExpiraMillis = when (val campo = document.get("data_expiracao")) {
                        is Number -> campo.toLong()
                        is String -> campo.toLongOrNull() ?: 0L
                        else -> 0L
                    }

                    val agora = System.currentTimeMillis()

                    if (status == "APROVADO" && dataExpiraMillis > agora) {
                        salvarAtivacao(nomeFiscal, dataExpiraMillis)
                        Toast.makeText(this, "Bem-vindo, $nomeFiscal!", Toast.LENGTH_SHORT).show()
                        irParaHub()
                    } else {
                        Toast.makeText(this, "Acesso negado ou licença inválida!", Toast.LENGTH_LONG).show()
                    }
                } else {
                    Toast.makeText(this, "Aparelho não cadastrado no sistema!", Toast.LENGTH_LONG).show()
                }
            }.addOnFailureListener {
                Toast.makeText(this, "Erro de conexão! Verifique sua internet.", Toast.LENGTH_SHORT).show()
            }
    }

    // Verifica o Firestore sem interromper o usuário. Se houver mudança (bloqueio), o app reage.
    private fun checagemSilenciosaLicenca() {
        FirebaseFirestore.getInstance().collection("licencas").document(deviceId).get()
            .addOnSuccessListener { document ->
                if (document.exists()) {
                    val status = document.getString("status")
                    val dataExpiraNuvem = document.getLong("data_expiracao") ?: 0L
                    val agora = System.currentTimeMillis()

                    if (status == "BLOQUEADO" || (dataExpiraNuvem > 0 && agora > dataExpiraNuvem)) {
                        // Revoga acesso local
                        val prefs = getSharedPreferences("config_pref", MODE_PRIVATE)
                        prefs.edit().putBoolean("is_active", false).apply()

                        // Redireciona para ativação limpando a pilha de telas
                        val intent = Intent(this, AtivacaoActivity::class.java)
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                        startActivity(intent)
                        finish()
                    } else {
                        // Atualiza a data local silenciosamente se o admin deu mais prazo
                        val prefs = getSharedPreferences("config_pref", MODE_PRIVATE)
                        prefs.edit().putLong("data_expiracao", dataExpiraNuvem).apply()
                    }
                }
            }
    }

    private fun salvarAtivacao(nome: String, dataExpiracao: Long) {
        val prefs = getSharedPreferences("config_pref", MODE_PRIVATE)
        val agora = System.currentTimeMillis()
        prefs.edit().apply {
            putBoolean("is_active", true)
            putLong("data_expiracao", dataExpiracao)
            putLong("ultimo_acesso_seguro", agora)
            putString("nome_usuario", nome)
            apply()
        }
    }

    private fun estaAtivadoValido(): Boolean {
        val prefs = getSharedPreferences("config_pref", MODE_PRIVATE)
        val isActive = prefs.getBoolean("is_active", false)
        val dataExpiraSalva = prefs.getLong("data_expiracao", 0L)
        val ultimoAcessoSeguro = prefs.getLong("ultimo_acesso_seguro", 0L)
        val agora = System.currentTimeMillis()

        if (!isActive) return false

        // Proteção contra alteração manual de data (Retrocesso)
        if (agora < ultimoAcessoSeguro) {
            Toast.makeText(this, "Erro de integridade de data!", Toast.LENGTH_LONG).show()
            return false
        }

        // Proteção contra expiração (Vencimento)
        if (agora > dataExpiraSalva) {
            prefs.edit().putBoolean("is_active", false).apply()
            Toast.makeText(this, "Sua licença expirou!", Toast.LENGTH_LONG).show()
            return false
        }

        // Registra o rastro de tempo
        prefs.edit().putLong("ultimo_acesso_seguro", agora).apply()
        return true
    }

    private fun irParaHub() {
        val intent = Intent(this, HubActivity::class.java)
        startActivity(intent)
        finish()
    }
}