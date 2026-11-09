package com.haidass.roomlight

import android.app.Activity
import android.app.AlertDialog
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.io.PrintWriter
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLEncoder

class MainActivity : Activity() {
    companion object {
        const val SERVER_PORT = 8765
        const val PC_IP = "192.168.137.1"
    }

    private lateinit var status: TextView
    private lateinit var serverInfo: TextView
    private lateinit var lampState: TextView
    private lateinit var lampSwitch: Switch
    private lateinit var connectButton: Button
    private lateinit var secretStore: SecretStore

    @Volatile
    private var serverRunning = false
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var clientSocket: Socket? = null

    @Volatile
    private var clientWriter: PrintWriter? = null

    private var ignoreSwitch = false
    private var pendingOnNotification = false
    private var isConnected = false
    private var reportedOn: Boolean? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secretStore = SecretStore(this)
        buildScreen()
        setSwitchState(false)
        startTinyServer()
    }

    private fun buildScreen() {
        window.statusBarColor = Color.rgb(16, 34, 31)
        window.navigationBarColor = Color.rgb(16, 34, 31)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(24), dp(24), dp(24))
            setBackgroundColor(Color.rgb(16, 34, 31))
        }
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        toolbar.addView(label("ROOM LIGHT", 16f, Color.WHITE, true), LinearLayout.LayoutParams(0, dp(48), 1f))
        toolbar.addView(Button(this).apply {
            text = "Telegram settings"
            setOnClickListener { showTelegramSettings() }
        })
        root.addView(toolbar)
        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(label("RASPBERRY PI 4", 14f, Color.rgb(128, 174, 160), true))

        status = label("Starting tiny server…", 16f, Color.LTGRAY).apply {
            gravity = Gravity.CENTER
        }
        root.addView(status, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)))

        serverInfo = label("Target Server: $PC_IP:$SERVER_PORT", 13f, Color.rgb(128, 174, 160)).apply {
            gravity = Gravity.CENTER
        }
        root.addView(serverInfo, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36)))

        lampState = label("Lamp is OFF", 20f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
        }
        root.addView(lampState, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))

        lampSwitch = Switch(this).apply {
            text = ""
            textSize = 22f
            scaleX = 1.35f
            scaleY = 1.35f
            setOnCheckedChangeListener { _, checked -> if (!ignoreSwitch) requestLampState(checked) }
        }
        val switchHolder = LinearLayout(this).apply {
            gravity = Gravity.CENTER
            addView(lampSwitch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(100)))
        }
        root.addView(switchHolder, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(140)))

        connectButton = Button(this).apply {
            text = "Restart Tiny Server"
            setOnClickListener { restartTinyServer() }
        }
        root.addView(connectButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(54)))

        root.addView(View(this), LinearLayout.LayoutParams(1, 0, 1f))
        root.addView(label("Telegram alerts are sent when the Raspberry Pi 4 confirms the lamp is ON.", 13f, Color.rgb(166, 190, 181)).apply {
            gravity = Gravity.CENTER
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
        setContentView(root)
    }

    private fun startTinyServer() {
        if (serverRunning) return
        serverRunning = true
        updateServerAddresses()
        updateConnection("Waiting for Raspberry Pi 4 client on port $SERVER_PORT…")

        Thread {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress("0.0.0.0", SERVER_PORT))
                }
                serverSocket = server
                runOnUiThread {
                    updateServerAddresses()
                    updateConnection("Tiny server listening on port $SERVER_PORT — waiting for Pi 4…")
                }

                while (serverRunning) {
                    val socket = server.accept()
                    handleClientConnection(socket)
                }
            } catch (e: Exception) {
                if (serverRunning) {
                    runOnUiThread {
                        updateConnection("Server stopped (${e.message ?: "error"})")
                    }
                }
            }
        }.start()
    }

    private fun handleClientConnection(socket: Socket) {
        Thread {
            try {
                socket.tcpNoDelay = true
                socket.keepAlive = true

                // Close any previous client connection cleanly
                try { clientSocket?.close() } catch (_: Exception) { }

                val writer = PrintWriter(OutputStreamWriter(socket.getOutputStream(), Charsets.UTF_8), true)
                val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))

                clientSocket = socket
                clientWriter = writer
                val remoteIp = socket.inetAddress?.hostAddress ?: "Raspberry Pi 4"

                runOnUiThread {
                    isConnected = true
                    updateConnection("Connected to Raspberry Pi 4 ($remoteIp)")
                    setSwitchState(reportedOn ?: false)
                    connectButton.text = "Disconnect Raspberry Pi 4"
                    connectButton.setOnClickListener { disconnectClient() }
                }

                // Request current state from Raspberry Pi client upon connect
                writer.println("STATUS")

                while (serverRunning && !socket.isClosed) {
                    val rawLine = reader.readLine() ?: break
                    val line = rawLine.trim().uppercase()
                    when {
                        line == "PING" -> writer.println("PONG")
                        line == "ON" || line == "STATE ON" -> handleLampState(true)
                        line == "OFF" || line == "STATE OFF" -> handleLampState(false)
                    }
                }
            } catch (_: Exception) {
                // Client disconnected
            } finally {
                try { socket.close() } catch (_: Exception) { }
                if (clientSocket === socket) {
                    clientSocket = null
                    clientWriter = null
                    runOnUiThread {
                        isConnected = false
                        pendingOnNotification = false
                        setSwitchState(false)
                        updateConnection("Raspberry Pi 4 disconnected — listening on port $SERVER_PORT…")
                        connectButton.text = "Restart Tiny Server"
                        connectButton.setOnClickListener { restartTinyServer() }
                    }
                }
            }
        }.start()
    }

    private fun requestLampState(on: Boolean) {
        val writer = clientWriter
        val socket = clientSocket
        if (!isConnected || writer == null || socket == null || socket.isClosed) {
            setSwitchState(!on)
            Toast.makeText(this, "Wait for Raspberry Pi 4 client to connect first", Toast.LENGTH_SHORT).show()
            return
        }
        pendingOnNotification = on
        lampState.text = "Waiting for Raspberry Pi 4…"
        val command = if (on) "ON" else "OFF"

        Thread {
            try {
                writer.println(command)
                if (writer.checkError()) {
                    throw IllegalStateException("Write failed")
                }
            } catch (_: Exception) {
                runOnUiThread {
                    pendingOnNotification = false
                    setSwitchState(reportedOn ?: false)
                    lampState.text = "Command failed — lamp state unchanged"
                    Toast.makeText(this, "Could not send command to Raspberry Pi 4", Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    private fun handleLampState(on: Boolean) {
        runOnUiThread {
            val wasOn = reportedOn == true
            reportedOn = on
            setSwitchState(on)
            lampState.text = if (on) "Lamp is ON" else "Lamp is OFF"
            if (on && (pendingOnNotification || !wasOn)) {
                sendTelegramNotification()
            }
            pendingOnNotification = false
        }
    }

    private fun disconnectClient() {
        pendingOnNotification = false
        Thread {
            try { clientSocket?.close() } catch (_: Exception) { }
        }.start()
    }

    private fun restartTinyServer() {
        stopTinyServer()
        startTinyServer()
        Toast.makeText(this, "Tiny server restarted on port $SERVER_PORT", Toast.LENGTH_SHORT).show()
    }

    private fun stopTinyServer() {
        serverRunning = false
        try { clientSocket?.close() } catch (_: Exception) { }
        try { serverSocket?.close() } catch (_: Exception) { }
        clientSocket = null
        clientWriter = null
        serverSocket = null
        isConnected = false
    }

    private fun updateServerAddresses() {
        val localIps = mutableListOf<String>()
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces != null && interfaces.hasMoreElements()) {
                val iface = interfaces.nextElement()
                if (!iface.isUp || iface.isLoopback) continue
                val addrs = iface.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (addr is Inet4Address && !addr.isLoopbackAddress) {
                        localIps.add(addr.hostAddress ?: continue)
                    }
                }
            }
        } catch (_: Exception) { }

        val deviceIpText = if (localIps.isNotEmpty()) {
            "PC: $PC_IP:$SERVER_PORT | App: ${localIps.joinToString(", ")}:$SERVER_PORT"
        } else {
            "Target Server: $PC_IP:$SERVER_PORT"
        }
        serverInfo.text = deviceIpText
    }

    private fun sendTelegramNotification() {
        val token = secretStore.token()
        val chatId = secretStore.chatId()
        if (token.isBlank() || chatId.isBlank()) {
            Toast.makeText(this, "Add your Telegram bot token and chat ID in settings", Toast.LENGTH_LONG).show()
            return
        }
        Thread {
            var connection: HttpURLConnection? = null
            try {
                val url = URL("https://api.telegram.org/bot$token/sendMessage")
                connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 10_000
                    readTimeout = 10_000
                    doOutput = true
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded; charset=UTF-8")
                }
                val body = "chat_id=${encode(chatId)}&text=${encode("Room light turned ON")}".toByteArray()
                connection.outputStream.use { it.write(body) }
                val code = connection.responseCode
                runOnUiThread {
                    Toast.makeText(
                        this,
                        if (code in 200..299) "Telegram notification sent" else "Telegram notification failed ($code)",
                        Toast.LENGTH_SHORT
                    ).show()
                }
            } catch (_: Exception) {
                runOnUiThread { Toast.makeText(this, "Could not reach Telegram", Toast.LENGTH_SHORT).show() }
            } finally {
                connection?.disconnect()
            }
        }.start()
    }

    private fun showTelegramSettings() {
        val fields = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), 0)
        }
        val tokenInput = EditText(this).apply {
            hint = "Telegram bot token"
            setSingleLine(true)
            inputType = 0x00000081
            setText(secretStore.token())
        }
        val chatInput = EditText(this).apply {
            hint = "Your Telegram chat ID"
            setSingleLine(true)
            inputType = 2
            setText(secretStore.chatId())
        }
        fields.addView(tokenInput)
        fields.addView(chatInput)
        AlertDialog.Builder(this)
            .setTitle("Telegram notifications")
            .setMessage("Create a bot with BotFather, send it /start, then enter its token and your numeric chat ID. Values are encrypted on this device.")
            .setView(fields)
            .setPositiveButton("Save") { _, _ ->
                secretStore.save(tokenInput.text.toString().trim(), chatInput.text.toString().trim())
                Toast.makeText(this, "Telegram settings saved", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        stopTinyServer()
        super.onDestroy()
    }

    private fun setSwitchState(on: Boolean) {
        ignoreSwitch = true
        lampSwitch.isChecked = on
        lampSwitch.isEnabled = isConnected
        ignoreSwitch = false
    }

    private fun updateConnection(message: String) {
        status.text = message
    }

    private fun encode(value: String): String = URLEncoder.encode(value, "UTF-8")
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    private fun label(text: String, size: Float, color: Int, bold: Boolean = false): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
    }
}
