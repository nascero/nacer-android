package com.example.ircustomstudio

import android.content.Context
import android.hardware.ConsumerIrManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    private lateinit.var irManager: ConsumerIrManager
    private lateinit var tvStatus: TextView
    private lateinit var tvSignalInfo: TextView
    private lateinit var btnToggleScan: Button
    private lateinit var btnPrev: Button
    private lateinit var btnNext: Button
    private lateinit var btnSave: Button
    private lateinit var seekBarSpeed: SeekBar

    private val allSignals = mutableListOf<Pair<Int, Int>>()
    private var currentIndex = 0
    private var isScanning = false
    private var scanDelayMs: Long = 200

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var scanRunnable: Runnable

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        irManager = getSystemService(Context.CONSUMER_IR_SERVICE) as ConsumerIrManager

        if (!irManager.hasIrEmitter()) {
            Toast.makeText(this, "Aucun émetteur IR détecté sur cet appareil !", Toast.LENGTH_LONG).show()
        }

        tvStatus = findViewById(R.id.tvStatus)
        tvSignalInfo = findViewById(R.id.tvSignalInfo)
        btnToggleScan = findViewById(R.id.btnToggleScan)
        btnPrev = findViewById(R.id.btnPrev)
        btnNext = findViewById(R.id.btnNext)
        btnSave = findViewById(R.id.btnSave)
        seekBarSpeed = findViewById(R.id.seekBarSpeed)

        // Génération de la liste des signaux (Adresses courantes + Commandes 0..255)
        val addresses = listOf(0x00, 0xEF, 0x40, 0xBF, 0xFE, 0x04)
        for (addr in addresses) {
            for (cmd in 0..255) {
                allSignals.add(Pair(addr, cmd))
            }
        }

        updateInfoText()

        // Gestion du curseur de vitesse (de 50ms à 2000ms)
        seekBarSpeed.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                scanDelayMs = (progress + 50).toLong()
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        // Bouton Démarrer / Pause
        btnToggleScan.setOnClickListener {
            if (isScanning) {
                stopScan()
            } else {
                startScan()
            }
        }

        // Configuration des boutons dynamiques (clic simple = 1 pas, maintien = défilement rapide)
        setupDynamicButton(btnPrev, { stepBackward() }, { stepBackward() })
        setupDynamicButton(btnNext, { stepForward() }, { stepForward() })

        // Sauvegarde du signal actuel
        btnSave.setOnClickListener {
            val (addr, cmd) = allSignals[currentIndex]
            Toast.makeText(this, "Signal enregistré ! (Addr: $addr, Cmd: $cmd)", Toast.LENGTH_LONG).show()
        }

        // Boucle de balayage automatique
        scanRunnable = object : Runnable {
            override fun run() {
                if (isScanning) {
                    sendCurrentSignal()
                    if (currentIndex < allSignals.size - 1) {
                        currentIndex++
                        updateInfoText()
                        handler.postDelayed(this, scanDelayMs)
                    } else {
                        stopScan()
                        Toast.makeText(this@MainActivity, "Fin du balayage", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun startScan() {
        isScanning = true
        btnToggleScan.text = "⏸ PAUSE (La TV a réagi !)"
        btnToggleScan.setBackgroundColor(android.graphics.Color.parseColor("#F44336"))
        btnSave.visibility = View.GONE
        tvStatus.text = "Balayage en cours..."
        handler.post(scanRunnable)
    }

    private fun stopScan() {
        isScanning = false
        btnToggleScan.text = "▶ Reprendre le balayage"
        btnToggleScan.setBackgroundColor(android.graphics.Color.parseColor("#2196F3"))
        btnSave.visibility = View.VISIBLE
        tvStatus.text = "En pause (Testez ou ajustez)"
        handler.removeCallbacks(scanRunnable)
    }

    private fun stepForward() {
        if (!isScanning && currentIndex < allSignals.size - 1) {
            currentIndex++
            updateInfoText()
            sendCurrentSignal()
        }
    }

    private fun stepBackward() {
        if (!isScanning && currentIndex > 0) {
            currentIndex--
            updateInfoText()
            sendCurrentSignal()
        }
    }

    private fun updateInfoText() {
        tvSignalInfo.text = "Signal ${currentIndex + 1} / ${allSignals.size}"
    }

    private fun sendCurrentSignal() {
        if (irManager.hasIrEmitter()) {
            val (addr, cmd) = allSignals[currentIndex]
            val pattern = generateNecPattern(addr, cmd)
            irManager.transmit(38000, pattern)
        }
    }

    // Générateur de trame NEC brute
    private fun generateNecPattern(address: Int, command: Int): IntArray {
        val list = mutableListOf<Int>()
        list.add(9000)
        list.add(4500)

        fun addByte(b: Int) {
            for (i in 0..7) {
                list.add(560)
                if ((b and (1 shl i)) != 0) {
                    list.add(1690)
                } else {
                    list.add(560)
                }
            }
        }

        addByte(address)
        addByte(255 - address)
        addByte(command)
        addByte(255 - command)
        list.add(560)
        return list.toIntArray()
    }

    // Gestion du toucher : clic simple ou maintien prolongé pour défilement rapide
    private fun setupDynamicButton(button: View, onStep: () -> Unit, onFastScroll: () -> Unit) {
        val actionHandler = Handler(Looper.getMainLooper())
        var isHolding = false

        val fastScrollRunnable = object : Runnable {
            override fun run() {
                if (isHolding) {
                    onFastScroll()
                    actionHandler.postDelayed(this, 60) // Vitesse de répétition en maintenant
                }
            }
        }

        button.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    isHolding = true
                    actionHandler.postDelayed({
                        if (isHolding) {
                            actionHandler.post(fastScrollRunnable)
                        }
                    }, 300) // Délai avant le début du défilement continu
                    true
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (isHolding && !actionHandler.hasCallbacks(fastScrollRunnable)) {
                        onStep() // Clic simple
                    }
                    isHolding = false
                    actionHandler.removeCallbacksAndMessages(null)
                    true
                }
                else -> false
            }
        }
    }
}
