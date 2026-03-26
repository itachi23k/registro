package com.cj.registrodecampo

import android.content.Context
import kotlin.math.*
import java.io.BufferedReader
import java.io.InputStreamReader

data class MarcoKm(
    val estaca: Int,
    val contrato: String,
    val lat: Double,
    val lon: Double
)

class CalculadoraObra(private val context: Context) {

    private val marcos = mutableListOf<MarcoKm>()

    init {
        carregarMarcosDosAssets()
    }

    private fun carregarMarcosDosAssets() {
        try {
            // Abre o arquivo que você colou na pasta assets
            val inputStream = context.assets.open("estacas_cheias.txt")
            val reader = BufferedReader(InputStreamReader(inputStream))

            reader.forEachLine { linha ->
                if (linha.contains(";")) {
                    val partes = linha.split(";")
                    if (partes.size >= 4) {
                        marcos.add(MarcoKm(
                            estaca = partes[0].toInt(),
                            contrato = partes[1],
                            lat = partes[2].toDouble(),
                            lon = partes[3].toDouble()
                        ))
                    }
                }
            }
            reader.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun localizarMaisProximo(latAtual: Double, lonAtual: Double): MarcoKm? {
        if (marcos.isEmpty()) return null

        return marcos.minByOrNull { marco ->
            val r = 6371000.0
            val dLat = Math.toRadians(marco.lat - latAtual)
            val dLon = Math.toRadians(marco.lon - lonAtual)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(latAtual)) * cos(Math.toRadians(marco.lat)) *
                    sin(dLon / 2).pow(2)
            val c = 2 * atan2(sqrt(a), sqrt(1 - a))
            r * c
        }
    }
}