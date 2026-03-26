package com.cj.registrodecampo

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.bumptech.glide.Glide // Recomendo adicionar Glide no build.gradle para miniaturas
import java.io.File

class SyncAdapter(private val listaArquivos: List<File>) : RecyclerView.Adapter<SyncAdapter.ViewHolder>() {

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.ivThumbSync)
        val tvNome: TextView = view.findViewById(R.id.tvNomeArquivoSync)
        val tvInfo: TextView = view.findViewById(R.id.tvInfoArquivoSync)
        val ivStatus: ImageView = view.findViewById(R.id.ivStatusSync)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_sync_pendencia, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val arquivo = listaArquivos[position]
        holder.tvNome.text = arquivo.name
        holder.tvInfo.text = "${arquivo.length() / 1024} KB"

        // Carrega a miniatura da foto (Usando Glide é mais rápido, mas pode usar o básico do Android)
        holder.ivThumb.setImageURI(android.net.Uri.fromFile(arquivo))
    }

    override fun getItemCount() = listaArquivos.size
}