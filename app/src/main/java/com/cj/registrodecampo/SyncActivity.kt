package com.cj.registrodecampo

import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import okhttp3.ResponseBody
import retrofit2.Response
import retrofit2.Retrofit
import retrofit2.http.Multipart
import retrofit2.http.POST
import retrofit2.http.Part
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class SyncActivity : ComponentActivity() {

    private val arquivosPendentes = mutableStateListOf<File>()

        private var tvResumoSync by mutableStateOf("0 fotos aguardando sincronismo")
    private var pbGlobalVisible by mutableStateOf(false)
    private var pbGlobalProgress by mutableFloatStateOf(0f)

    private val job = Job()
    private val scope = CoroutineScope(Dispatchers.Main + job)

    private val baseUrlOracle = "http://147.15.3.32:5000/"
    private lateinit var oracleApi: OracleApi

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        enableEdgeToEdge()

        FirebaseApp.initializeApp(this)
        val storage = FirebaseStorage.getInstance()
        storage.maxUploadRetryTimeMillis = 3000
        storage.maxOperationRetryTimeMillis = 20000

        inicializarRetrofit()
        carregarArquivosPendentes()

        setContent {
            MaterialTheme {
                TelaSincronismoCompose()
            }
        }
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    fun TelaSincronismoCompose() {
        Scaffold(
            topBar = {
                TopAppBar(
                    modifier = Modifier.windowInsetsPadding(WindowInsets.statusBars),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                painter = painterResource(id = android.R.drawable.stat_notify_sync),
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(32.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text(
                                text = "Sincronismo de Fotos",
                                color = Color.White,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFFF5722))
                )
            },
            bottomBar = {
                Column(
                    modifier = Modifier
                        .background(Color.White)
                        .windowInsetsPadding(WindowInsets.navigationBars)
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Button(
                        onClick = { iniciarUploadEmMassa(usarOracle = true) },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFCC80))
                    ) {
                        Text("ENVIAR ORACLE (EXPRESSO)", color = Color(0xFF333333), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Button(
                        onClick = { iniciarUploadEmMassa(usarOracle = false) },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF5722))
                    ) {
                        Text("SINCRONIZAR AGORA (FIREBASE)", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.White)
                    .padding(paddingValues)
            ) {
                Text(
                    text = tvResumoSync,
                    color = Color(0xFF333333),
                    fontSize = 14.sp,
                    modifier = Modifier
                        .padding(top = 16.dp)
                        .align(Alignment.CenterHorizontally)
                )

                if (pbGlobalVisible) {
                    LinearProgressIndicator(
                        progress = { pbGlobalProgress },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp, vertical = 12.dp),
                        color = Color(0xFFFF5722),
                        trackColor = Color(0xFFE0E0E0),
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 12.dp)
                ) {
                    itemsIndexed(arquivosPendentes) { _, arquivo ->
                        ItemFilaLayout(arquivo = arquivo)
                    }
                }
            }
        }
    }

    @Composable
    fun ItemFilaLayout(arquivo: File) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = android.R.drawable.ic_menu_gallery),
                contentDescription = null,
                modifier = Modifier.size(40.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column {
                Text(text = arquivo.name, color = Color(0xFF333333), fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Text(text = "${arquivo.length() / 1024} KB", color = Color.Gray, fontSize = 12.sp)
            }
        }
    }

    private fun inicializarRetrofit() {
        val okHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        val retrofit = Retrofit.Builder()
            .baseUrl(baseUrlOracle)
            .client(okHttpClient)
            .build()

        oracleApi = retrofit.create(OracleApi::class.java)
    }

    private fun carregarArquivosPendentes() {
        val root = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
        val pastaRaiz = File(root, "Medicoes_BR319")

        if (!pastaRaiz.exists()) {
            tvResumoSync = "Nenhuma pasta encontrada"
            return
        }

        val arquivoLog = File(getExternalFilesDir(null), "registro_sync.txt")
        if (!arquivoLog.exists()) arquivoLog.createNewFile()

        val nomesEnviados = arquivoLog.readLines().map { it.split("|")[0] }
        arquivosPendentes.clear()

        val listaLocal = mutableListOf<File>()
        pastaRaiz.walkTopDown().forEach { file ->
            if (file.isFile && file.extension.lowercase() == "jpg" && !nomesEnviados.contains(file.name)) {
                listaLocal.add(file)
            }
        }
        arquivosPendentes.addAll(listaLocal)
        tvResumoSync = "${arquivosPendentes.size} fotos aguardando sincronismo"
    }

    private fun iniciarUploadEmMassa(usarOracle: Boolean) {
        if (arquivosPendentes.isEmpty()) {
            Toast.makeText(this, "Nada para sincronizar!", Toast.LENGTH_SHORT).show()
            return
        }

        pbGlobalProgress = 0f
        pbGlobalVisible = true

        val nuvemNome = if (usarOracle) "Oracle Cloud" else "Firebase"
        tvResumoSync = "Sincronizando com $nuvemNome..."

        val listaParaEnvio = ArrayList(arquivosPendentes)
        val totalArquivos = listaParaEnvio.size.toFloat()

        scope.launch {
            val semaforo = Semaphore(3)

            val tarefas = listaParaEnvio.map { arquivo ->
                async(Dispatchers.IO) {
                    semaforo.withPermit {
                        tentarUploadComRetry(arquivo, 3, usarOracle)
                        withContext(Dispatchers.Main) {
                            pbGlobalProgress += (1f / totalArquivos)
                        }
                    }
                }
            }

            tarefas.awaitAll()

            withContext(Dispatchers.Main) {
                pbGlobalVisible = false
                tvResumoSync = "Sincronização Finalizada ($nuvemNome)"
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

    private suspend fun processarUploadFirebase(file: File) {
        val caminhoNoStorage = "registros/${obterCaminhoRelativo(file)}"
        val storageRef = FirebaseStorage.getInstance().reference.child(caminhoNoStorage)

        storageRef.putFile(Uri.fromFile(file)).await()

        withContext(Dispatchers.Main) { atualizarUIEApagar(file) }
    }

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

    private fun atualizarUIEApagar(file: File) {
        registrarNoLog(file.name)
        arquivosPendentes.remove(file)
        tvResumoSync = "${arquivosPendentes.size} fotos restantes"
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

interface OracleApi {
    @Multipart
    @POST("upload")
    suspend fun enviarFoto(
        @Part foto: MultipartBody.Part,
        @Part("caminho") caminho: okhttp3.RequestBody
    ): Response<ResponseBody>
}
