package com.example.motoristainteligente

import android.view.View
import android.widget.TextView

fun bindStatusCardCloseAction(card: View, onClose: () -> Unit) {
    card.findViewById<View>(R.id.btnCloseStatus).setOnClickListener { onClose() }
}

fun bindStatusCardSessionTime(card: View, sessionDurationMin: Int) {
    val hours = sessionDurationMin / 60
    val mins = sessionDurationMin % 60
    card.findViewById<TextView>(R.id.tvSessionTime).text =
        if (hours > 0) "${hours}h ${mins}min" else "${mins}min"
}
