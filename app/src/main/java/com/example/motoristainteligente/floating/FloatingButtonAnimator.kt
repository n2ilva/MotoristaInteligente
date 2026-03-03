package com.example.motoristainteligente

import android.view.View

fun animateFloatingButtonPulse(button: View?) {
    button?.let { btn ->
        btn.animate()
            .scaleX(1.3f).scaleY(1.3f)
            .setDuration(200)
            .withEndAction {
                btn.animate()
                    .scaleX(1f).scaleY(1f)
                    .setDuration(200)
                    .start()
            }
            .start()
    }
}

fun animateFloatingButtonWarningPulse(button: View?) {
    button?.let { btn ->
        btn.animate()
            .scaleX(1.4f).scaleY(1.4f)
            .alpha(0.5f)
            .setDuration(300)
            .withEndAction {
                btn.animate()
                    .scaleX(1f).scaleY(1f)
                    .alpha(1f)
                    .setDuration(300)
                    .withEndAction {
                        btn.animate()
                            .scaleX(1.4f).scaleY(1.4f)
                            .alpha(0.5f)
                            .setDuration(300)
                            .withEndAction {
                                btn.animate()
                                    .scaleX(1f).scaleY(1f)
                                    .alpha(1f)
                                    .setDuration(300)
                                    .start()
                            }
                            .start()
                    }
                    .start()
            }
            .start()
    }
}
