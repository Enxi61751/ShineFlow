package com.android.calendar.ui

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.android.calendar.ml.common.MlResult
import com.android.calendar.ml.gnn.GnnRepository
import com.android.calendar.ml.gnn.GnnSuggestRequest
import com.android.calendar.ml.stn.StnRectifier
import ws.xsoh.etar.R
import kotlinx.coroutines.*

class MlDebugActivity : AppCompatActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private lateinit var imgPreview: ImageView
    private lateinit var txtOutput: TextView

    private var lastImageUri: Uri? = null
    private var rectifiedOk: Boolean = false

    private val pickImageReqCode = 1001

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_ml_debug)

        imgPreview = findViewById(R.id.imgPreview)
        txtOutput = findViewById(R.id.txtOutput)

        findViewById<Button>(R.id.btnPickImage).setOnClickListener {
            val intent = Intent(Intent.ACTION_GET_CONTENT).apply {
                type = "image/*"
            }
            startActivityForResult(Intent.createChooser(intent, "Select image"), pickImageReqCode)
        }

        findViewById<Button>(R.id.btnCallGnn).setOnClickListener {
            scope.launch {
                callGnnDemo()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == pickImageReqCode && resultCode == Activity.RESULT_OK) {
            lastImageUri = data?.data
            rectifiedOk = false
            if (lastImageUri != null) {
                scope.launch {
                    runStnRectify(lastImageUri!!)
                }
            }
        }
    }

    private suspend fun runStnRectify(uri: Uri) = withContext(Dispatchers.IO) {
        val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
        if (bytes == null) {
            withContext(Dispatchers.Main) { txtOutput.text = "Open image failed" }
            return@withContext
        }

        val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        val rectifier = StnRectifier(this@MlDebugActivity)

        val result = rectifier.rectify(bmp)
        rectifier.close()

        withContext(Dispatchers.Main) {
            when (result) {
                is MlResult.Ok -> {
                    imgPreview.setImageBitmap(result.value)
                    txtOutput.text = "STN rectify OK (224x224). Now you can feed to OCR."
                    rectifiedOk = true
                }
                is MlResult.Err -> {
                    txtOutput.text = "STN rectify ERROR: ${result.message}"
                }
            }
        }
    }

    private suspend fun callGnnDemo() {
        // 注意：把这个换成你电脑/服务器的地址（同一局域网）
        // Android 模拟器可用 10.0.2.2 指向宿主机
        val baseUrl = "http://https://u836809-92e6-37d8b4ba.bjb2.seetacloud.com:8443/"
        val repo = GnnRepository(baseUrl)

        val req = GnnSuggestRequest(
            userId = "demo_user",
            recentEventTitles = listOf("Team meeting", "Gym", "Dinner"),
            recentEventLocations = listOf("Office", "Fitness", "Downtown"),
            recentEventHours = listOf(10, 19, 20),
            topK = 5
        )

        txtOutput.text = "Calling GNN..."
        val result = withContext(Dispatchers.IO) { repo.suggestHours(req) }

        when (result) {
            is MlResult.Ok -> {
                txtOutput.text = "GNN suggested hours: ${result.value.suggestedHours}\nconf: ${result.value.confidences}"
            }
            is MlResult.Err -> {
                txtOutput.text = "GNN ERROR: ${result.message}"
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
