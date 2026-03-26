package com.cj.registrodecampo

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.view.View
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.firebase.FirebaseApp
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.tasks.await
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import okhttp3.ResponseBody

class SyncActivity : AppCompatActivity() {

    private lateinit var adapter: SyncAdapter
    private val arquivosPendentes = mutableListOf<File>()
    private lateinit var pbGlobal: ProgressBar
    private lateinit var tvResumo: TextView

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    // --- CONFIGURAÇÃO ORACLE ---
    // Substitua pelo IP Público da sua VM ou DNS (ex: http://123.45.67.89:5000/)
    // IMPORTANTE: Mantenha a barra "/" no final do link
    private val BASE_URL_ORACLE = "http://147.15.3.32:5000/"
    private lateinit var oracleApi: OracleApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_sync)

        FirebaseApp.initializeApp(this)

        val storage = FirebaseStorage.getInstance()
        storage.maxUploadRetryTimeMillis = 3000
        storage.maxOperationRetryTimeMillis = 20000

        // Inicializa o motor de comunicação HTTP
        inicializarRetrofit()

        pbGlobal = findViewById(R.id.pbGlobalSync)
        tvResumo = findViewById(R.id.tvResumoSync)
        val rv = findViewById<RecyclerView>(R.id.rvPendenciasSync)

        val btnSyncFirebase = findViewById<MaterialButton>(R.id.btnIniciarSync)
        val btnSyncOracle = findViewById<MaterialButton>(R.id.btnEnviarOracle) // O novo botão

        rv.layoutManager = LinearLayoutManager(this)
        carregarArquivosPendentes()

        adapter = SyncAdapter(arquivosPendentes)
        rv.adapter = adapter

        // Rotear os botões passando a flag
        btnSyncFirebase.setOnClickListener { iniciarUploadEmMassa(usarOracle = false) }
        btnSyncOracle.setOnClickListener { iniciarUploadEmMassa(usarOracle = true) }
    }

    private fun inicializarRetrofit() {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(BASE_URL_ORACLE)
            .client(okHttpClient)
            .build()

        oracleApi = retrofit.create(OracleApi::class.java)
    }

    private fun carregarArquivosPendentes() {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val pastaRaiz = File(root, "Medicoes_BR319")

        if (!pastaRaiz.exists()) {
            tvResumo.text = "Nenhuma pasta encontrada"
            return
        }

        val arquivoLog = File(getExternalFilesDir(null), "registro_sync.txt")
        if (!arquivoLog.exists()) arquivoLog.createNewFile()

        val nomesEnviados = arquivoLog.readLines().map { it.split("|")[0] }
        arquivosPendentes.clear()

        pastaRaiz.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() == "jpg" && !nomesEnviados.contains(file.name)) {
                arquivosPendentes.add(file)
            }
        }
        tvResumo.text = "${arquivosPendentes.size} fotos aguardando sincronismo"
    }

    private fun iniciarUploadEmMassa(usarOracle: Boolean) {
        if (arquivosPendentes.isEmpty()) {
            Toast.makeText(this, "Nada para sincronizar!", Toast.LENGTH_SHORT).show()
            return
        }

        pbGlobal.visibility = View.VISIBLE
        pbGlobal.progress = 0
        pbGlobal.max = arquivosPendentes.size

        val nuvemNome = if (usarOracle) "Oracle Cloud" else "Firebase"
        tvResumo.text = "Sincronizando com $nuvemNome..."

        val listaParaEnvio = ArrayList(arquivosPendentes)

        scope.launch {
            val semaforo = Semaphore(3)

            val tarefas = listaParaEnvio.map { arquivo ->
                async(Dispatchers.IO) {
                    semaforo.withPermit {
                        tentarUploadComRetry(arquivo, 3, usarOracle)
                    }
                }
            }

            tarefas.awaitAll()

            withContext(Dispatchers.Main) {
                pbGlobal.visibility = View.GONE
                tvResumo.text = "Sincronização Finalizada ($nuvemNome)"
                limparPastasVazias()
                Toast.makeText(this@SyncActivity, "Fotos processadas!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private suspend fun tentarUploadComRetry(file: File, tentativas: Int, usarOracle: Boolean) {
        var atual = 0
        var sucesso = false

        while (atual < tentativas && !sucesso) {
            atual++
            try {
                if (usarOracle) {
                    processarUploadOracle(file)
                } else {
                    processarUploadFirebase(file)
                }
                sucesso = true
            } catch (e: Exception) {
                if (atual >= tentativas) {
                    val logTag = if (usarOracle) "SYNC_ORACLE" else "SYNC_FIREBASE"
                    Log.e(logTag, "Falha definitiva: ${file.name} | Erro: ${e.message}")
                } else {
                    delay(2000L * atual)
                }
            }
        }
    }

    // --- ROTA 1: ORACLE CLOUD (RETROFIT) ---
    private suspend fun processarUploadOracle(file: File) {
        val caminhoForm = obterCaminhoRelativo(file)

        val requestFile = file.asRequestBody("image/jpeg".toMediaTypeOrNull())
        val bodyFoto = MultipartBody.Part.createFormData("foto", file.name, requestFile)
        val requestPath = caminhoForm.toRequestBody("text/plain".toMediaTypeOrNull())

        val response = oracleApi.enviarFoto(bodyFoto, requestPath)

        if (response.isSuccessful) {
            withContext(Dispatchers.Main) { atualizarUIEApagar(file) }
        } else {
            throw Exception("HTTP Erro: ${response.code()}")
        }
    }

    // --- ROTA 2: FIREBASE (SDK) ---
    private suspend fun processarUploadFirebase(file: File) {
        val caminhoNoStorage = "registros/${obterCaminhoRelativo(file)}"
        val storageRef = FirebaseStorage.getInstance().reference.child(caminhoNoStorage)

        storageRef.putFile(Uri.fromFile(file)).await()

        withContext(Dispatchers.Main) { atualizarUIEApagar(file) }
    }

    // --- FUNÇÕES AUXILIARES ---

    // Descobre se a foto está dentro de GERAL ou dentro de ESTACA
    private fun obterCaminhoRelativo(file: File): String {
        val pastaServico = file.parentFile?.name ?: "GERAL"
        val pastaContrato = file.parentFile?.parentFile?.name ?: "CONTRATO"

        val contratoFinal = if (pastaServico.startsWith("Estaca")) {
            file.parentFile?.parentFile?.parentFile?.name ?: "CONTRATO"
        } else { pastaContrato }

        val servicoFinal = if (pastaServico.startsWith("Estaca")) {
            file.parentFile?.parentFile?.name ?: "SERVICO"
        } else { pastaServico }

        return "$contratoFinal/$servicoFinal/${file.name}"
    }

    // Executada após o SUCESSO de qualquer nuvem
    private fun atualizarUIEApagar(file: File) {
        registrarNoLog(file.name)
        val index = arquivosPendentes.indexOf(file)
        if (index != -1) {
            arquivosPendentes.removeAt(index)
            adapter.notifyItemRemoved(index)
        }
        pbGlobal.progress += 1
        tvResumo.text = "${arquivosPendentes.size} fotos restantes"

        if (file.exists()) file.delete()
    }

    private fun registrarNoLog(nome: String) {
        val arquivoLog = File(getExternalFilesDir(null), "registro_sync.txt")
        val dataHoje = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        arquivoLog.appendText("$nome|ENVIADO|$dataHoje\n")
    }

    private fun limparPastasVazias() {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val pastaRaiz = File(root, "Medicoes_BR319")
        if (pastaRaiz.exists()) {
            pastaRaiz.walkBottomUp().forEach { file ->
                if (file.isDirectory && file.name != "Medicoes_BR319") {
                    val filhos = file.listFiles()
                    if (filhos.isNullOrEmpty()) file.delete()
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        job.cancel()
    }
}

// Interface do Retrofit colada aqui para facilitar (o ideal é um arquivo separado, mas funciona perfeitamente assim)
interface OracleApi {
    @Multipart
    @POST("upload")
    suspend fun enviarFoto(
        @Part foto: MultipartBody.Part,
        @Part("caminho") caminho: okhttp3.RequestBody
    ): Response<ResponseBody>
}