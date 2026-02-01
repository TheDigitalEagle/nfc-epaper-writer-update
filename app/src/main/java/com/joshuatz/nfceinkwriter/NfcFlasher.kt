package com.joshuatz.nfceinkwriter

import android.app.PendingIntent
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.nfc.NdefMessage
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.IsoDep
import android.nfc.tech.NfcA
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PatternMatcher
import android.os.SystemClock
import android.text.format.DateFormat
import android.util.Log
import android.widget.Button
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import waveshare.feng.nfctag.activity.WaveShareHandler
import waveshare.feng.nfctag.activity.a
import java.io.IOException
import java.nio.charset.StandardCharsets

class NfcFlasher : AppCompatActivity() {
    private var mIsFlashing = false
        get() = field
        set(isFlashing) {
            field = isFlashing
            // Hide or show flashing UI
            this.mWhileFlashingArea?.visibility = if (isFlashing) android.view.View.VISIBLE else android.view.View.GONE
            this.mWhileFlashingArea?.requestLayout()
            // Regardless of state change, progress should be reset to zero
            this.mProgressVal = 0
        }
    private var mNfcAdapter: NfcAdapter? = null
    private var mPendingIntent: PendingIntent? = null
    private var mNfcTechList = arrayOf(arrayOf(NfcA::class.java.name))
    private var mNfcIntentFilters: Array<IntentFilter>? = null
    private var mNfcCheckHandler: Handler? = null
    private val mNfcCheckIntervalMs = 250L
    private val mProgressCheckInterval = 50L
    private var mProgressBar: ProgressBar? = null
    private var mProgressVal: Int = 0
    private var mBitmap: Bitmap? = null
    private var mWhileFlashingArea: ConstraintLayout? = null
    private val mLogBuffer: ArrayDeque<String> = ArrayDeque(200)
    private val mMaxLogLines = 200
    private var mImgFilePath: String? = null
    private var mImgFileUri: Uri? = null

    // Note: Use of object expression / anon class is so `this` can be used
    // for reference to runnable (which would normally be off-limits)
    private val mNfcCheckCallback: Runnable = object: Runnable {
        override fun run() {
            checkNfcAndAttemptRecover()
            // Loop!
            mNfcCheckHandler?.postDelayed(this, mNfcCheckIntervalMs)
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (mImgFileUri != null) {
            outState.putString("serializedGeneratedImgUri",mImgFileUri.toString())
        }
    }

    // @TODO - change intent to just pass raw bytearr? Cleanup path usage?
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_nfc_flasher)

        /**
         * Saved bitmap handling
         */
        val savedUriStr = savedInstanceState?.getString("serializedGeneratedImgUri")
        if (savedUriStr != null) {
            mImgFileUri = Uri.parse(savedUriStr)
        } else {
            val intentExtras = intent.extras
            mImgFilePath = intentExtras?.getString(IntentKeys.GeneratedImgPath)
            if (mImgFilePath != null) {
                // @TODO - handle exceptions, navigate back to prev activity
                val fileRef = getFileStreamPath(mImgFilePath)
                mImgFileUri = Uri.fromFile(fileRef)
            }
        }
        if (mImgFileUri == null) {
            // Fallback to last generated image
            val fileRef = getFileStreamPath(GeneratedImageFilename)
            mImgFileUri = Uri.fromFile(fileRef)
        }

        val imagePreviewElem: ImageView = findViewById(R.id.previewImageView)
        imagePreviewElem.setImageURI(mImgFileUri)

        if (mImgFileUri != null) {
            val bmOptions = BitmapFactory.Options()
            this.mBitmap = BitmapFactory.decodeFile(mImgFileUri!!.path, bmOptions)
        }

        /**
         * Actual flasher stuff
         */

        mWhileFlashingArea  = findViewById(R.id.whileFlashingArea)
        mProgressBar = findViewById(R.id.nfcFlashProgressbar)
        val viewLogsButton: Button = findViewById(R.id.viewLogsButton)
        val bruteForceCheckbox: CheckBox = findViewById(R.id.bruteForceSizesCheckbox)
        viewLogsButton.setOnClickListener { showLogsDialog() }

        val originatingIntent = intent

        // Set up intent and intent filters for NFC / NDEF scanning
        // This is part of the setup for foreground dispatch system
        val nfcIntent = Intent(this, javaClass).apply {
            addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val mutableFlag = if (Build.VERSION.SDK_INT >= 31) 0x02000000 else 0
        val pendingIntentFlags = PendingIntent.FLAG_UPDATE_CURRENT or mutableFlag
        this.mPendingIntent = PendingIntent.getActivity(this, 0, nfcIntent, pendingIntentFlags)
        // Set up the filters
        var ndefIntentFilter: IntentFilter = IntentFilter(NfcAdapter.ACTION_NDEF_DISCOVERED)
        try {
            // android:host
            ndefIntentFilter.addDataAuthority("ext", null)

            // android:pathPattern
            // allow all data paths - see notes below
            ndefIntentFilter.addDataPath(".*", PatternMatcher.PATTERN_SIMPLE_GLOB)
            // NONE of the below work, although at least one or more should
            // I think because the payload isn't getting extracted out into the intent by Android
            // Debugging shows mData.path = null, which makes no sense (it definitely is not, and if
            // I don't intercept AAR, Android definitely tries to open the corresponding app...
            //ndefIntentFilter.addDataPath("waveshare.feng.nfctag.*", PatternMatcher.PATTERN_SIMPLE_GLOB);
            //ndefIntentFilter.addDataPath(".*waveshare\\.feng\\.nfctag.*", PatternMatcher.PATTERN_SIMPLE_GLOB);
            //ndefIntentFilter.addDataPath("waveshare.feng.nfctag", PatternMatcher.PATTERN_LITERAL);
            //ndefIntentFilter.addDataPath("waveshare\\.feng\\.nfctag", PatternMatcher.PATTERN_LITERAL);

            // android:scheme
            ndefIntentFilter.addDataScheme("vnd.android.nfc")
        } catch (e: IntentFilter.MalformedMimeTypeException) {
            Log.e("mimeTypeException", "Invalid / Malformed mimeType")
        }
        mNfcIntentFilters = arrayOf(
            ndefIntentFilter,
            IntentFilter(NfcAdapter.ACTION_TAG_DISCOVERED),
            IntentFilter(NfcAdapter.ACTION_TECH_DISCOVERED)
        )

        // Init NFC adapter
        mNfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (mNfcAdapter == null) {
            Toast.makeText(this, "NFC is not available on this device.", Toast.LENGTH_LONG).show()
        }

        // Start NFC check loop in case adapter dies
        startNfcCheckLoop()
    }
    override fun onPause() {
        super.onPause()
        this.stopNfcCheckLoop()
        this.disableForegroundDispatch()
    }

    override fun onResume() {
        super.onResume()
        this.startNfcCheckLoop()
        this.enableForegroundDispatch()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        Log.i("New intent", "New Intent: $intent")
        Log.v("Intent.action", intent.action ?: "no action")

        val preferences = Preferences(this)
        val screenSizeEnum = preferences.getScreenSizeEnum()
        val bruteForceSizes = bruteForceCheckbox.isChecked

        if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED || intent.action == NfcAdapter.ACTION_TAG_DISCOVERED || intent.action == NfcAdapter.ACTION_TECH_DISCOVERED) {
            val detectedTag: Tag = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)!!
            val tagIdAscii = String(detectedTag.id, StandardCharsets.US_ASCII)
            val tagIdHex = detectedTag.id.joinToString("") { b ->
                "%02X".format(b.toInt() and 0xFF)
            }
            val tagTechList = detectedTag.techList
            appendLog("NFC", "Intent ${intent.action} tech=${tagTechList.joinToString(",")} id=$tagIdHex")
            logTagDetails(detectedTag)

            // Do we still have a bitmap to flash?
            val bitmap = this.mBitmap
            if (bitmap == null) {
                appendLog("Missing bitmap", "mBitmap = null")
                Toast.makeText(this, "Missing bitmap to flash.", Toast.LENGTH_SHORT).show()
                return
            }

            // Check for correct NFC type support
            if (!tagTechList.contains(NfcA::class.java.name)) {
                appendLog("Invalid tag type", tagTechList.toString())
                Toast.makeText(this, "Unsupported NFC tag tech.", Toast.LENGTH_SHORT).show()
                return
            }

            // Log ID for diagnostics; don't gate on it to avoid false negatives.
            if (tagIdAscii !in WaveShareUIDs && tagIdHex !in WaveShareUIDs) {
                appendLog("Unknown tag ID", "$tagIdAscii / $tagIdHex not in " + WaveShareUIDs.joinToString(", "))
                Toast.makeText(this, "Unknown tag; attempting flash anyway.", Toast.LENGTH_SHORT).show()
            }

            // ACTION_NDEF_DISCOVERED has the filter applied for the AAR record *type*,
            // but the filter for the payload (dataPath / pathPattern) is not working, so as
            // an extra check, AAR payload will be manually checked, as well as ID
            if (intent.action == NfcAdapter.ACTION_NDEF_DISCOVERED) {
                var aarFound = false
                val rawMsgs = intent.getParcelableArrayExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)
                if (rawMsgs != null) {
                    for (msg in rawMsgs) {
                        val ndefMessage: NdefMessage = msg as NdefMessage
                        val records = ndefMessage.records
                        for (record in records) {
                            val payloadStr = String(record.payload)
                            if (!aarFound) aarFound = payloadStr == "waveshare.feng.nfctag"
                            if (aarFound) break
                        }
                        if (aarFound) break
                    }
                }

                if (!aarFound) {
                    appendLog("Bad NDEFs", "records found, but missing AAR")
                    Toast.makeText(this, "NDEF found, missing AAR; attempting flash anyway.", Toast.LENGTH_SHORT).show()
                }
            }

            if (!mIsFlashing) {
                // Here we go!!!
                appendLog("Matched!", "Tag is a match! Preparing to flash...")
                lifecycleScope.launch {
                    flashBitmap(detectedTag, bitmap, screenSizeEnum, bruteForceSizes)
                }
            } else {
                appendLog("Not flashing", "Flashing already in progress!")
            }
        }
    }

    private suspend fun flashBitmap(tag: Tag, bitmap: Bitmap, screenSizeEnum: Int, bruteForceSizes: Boolean) {
        this.mIsFlashing = true
        // val waveShareHandler = WaveShareHandler(this)
        val a = a() // Create a new instance.
        a.a() // Initialize SDK state (mirrors WaveShareHandler)
        val nfcObj = NfcA.get(tag)
        // Override WaveShare's SDK default of 700
        nfcObj.timeout = 1200
        var errorString = ""
        var initResult = -1
        var sendResult = -1

        val t: Thread = object : Thread() {
            //Create an new thread
            override fun run() {
                var success = false
                val tntag: NfcA //NFC tag
                val thread = Thread(Runnable
                //Create thread
                {
                    var EPD_total_progress = 0
                    while (EPD_total_progress != -1) {
                        EPD_total_progress = a.c //Read the progress
                        runOnUiThread(Runnable {
                            updateProgressBar(EPD_total_progress)
                        })
                        if (EPD_total_progress == 100) {
                            break
                        }
                        SystemClock.sleep(10)
                    }
                })
                thread.start() //start the thread
                tntag = NfcA.get(tag) //Get the tag instance.
                try {
                    if (!nfcObj.isConnected) {
                        nfcObj.connect()
                    }
                    initResult = a.a(nfcObj)
                    if (initResult != 1) {
                        errorString = "NFC init failed (code $initResult)"
                    } else {
                        val sizesToTry: List<Int> = if (bruteForceSizes) {
                            val allSizes = (1..ScreenSizes.size).toMutableList()
                            allSizes.remove(screenSizeEnum)
                            listOf(screenSizeEnum) + allSizes
                        } else {
                            listOf(screenSizeEnum)
                        }
                        if (bruteForceSizes) {
                            appendLog("Brute force", "Trying ${sizesToTry.size} sizes")
                        }
                        for (sizeEnum in sizesToTry) {
                            val sizeName = if (sizeEnum in 1..ScreenSizes.size) {
                                ScreenSizes[sizeEnum - 1]
                            } else {
                                "unknown"
                            }
                            sendResult = a.a(sizeEnum, bitmap) //Send picture
                            appendLog("Send attempt", "size=$sizeEnum ($sizeName) result=$sendResult")
                            if (sendResult == 1) {
                                success = true
                                break
                            } else if (sendResult == 2) {
                                errorString = "Incorrect image resolution (code $sendResult)"
                            } else {
                                errorString = "Write failed (code $sendResult)"
                            }
                        }
                    }
                } catch (e: IOException) {
                    errorString = "IO error: ${e.message ?: e.javaClass.simpleName}"
                } catch (e: Exception) {
                    errorString = "Error: ${e.message ?: e.javaClass.simpleName}"
                } finally {
                        try {
                            // Need to run toast on main thread...
                            runOnUiThread(Runnable {
                                var toast: Toast? = null
                                if (!success) {
                                    if (errorString.isBlank()) {
                                        errorString = "Unknown failure (init=$initResult send=$sendResult)"
                                    }
                                    toast = Toast.makeText(
                                        applicationContext,
                                        "FAILED to Flash :( $errorString",
                                        Toast.LENGTH_LONG
                                    )
                                } else {
                                    toast = Toast.makeText(
                                        applicationContext,
                                        "Success! Flashed display!",
                                        Toast.LENGTH_LONG
                                    )
                                }
                                toast?.show()
                            })
                            appendLog("Final success val", "Success = $success")
                            val screenName = if (screenSizeEnum in 1..ScreenSizes.size) {
                                ScreenSizes[screenSizeEnum - 1]
                            } else {
                                "unknown"
                            }
                            appendLog(
                                "Flash details",
                                "init=$initResult send=$sendResult screen=$screenSizeEnum ($screenName) timeout=${nfcObj.timeout} maxTx=${nfcObj.maxTransceiveLength}"
                            )
                            tntag.close()
                        } catch (e: IOException) { //handle exception error
                            e.printStackTrace()
                            appendLog("Flashing failed", "See trace above")
                        }
                        appendLog("Tag closed", "Setting flash in progress = false")
                        runOnUiThread(Runnable {
                            mIsFlashing = false
                        })
                }
            }
        }
        t.start() //Start thread
    }

    private fun enableForegroundDispatch() {
        this.mNfcAdapter?.enableForegroundDispatch(this, this.mPendingIntent, this.mNfcIntentFilters, this.mNfcTechList )
    }

    private fun disableForegroundDispatch() {
        this.mNfcAdapter?.disableForegroundDispatch(this)
    }

    private fun startNfcCheckLoop() {
        if (mNfcCheckHandler == null) {
            Log.v("NFC Check Loop", "START")
            mNfcCheckHandler = Handler(Looper.getMainLooper())
            mNfcCheckHandler?.postDelayed(mNfcCheckCallback, mNfcCheckIntervalMs)
        }
    }

    private fun stopNfcCheckLoop() {
        if (mNfcCheckHandler != null) {
            mNfcCheckHandler?.removeCallbacks(mNfcCheckCallback)
        }
        mNfcCheckHandler = null
    }

    private fun checkNfcAndAttemptRecover() {
        if (mNfcAdapter != null) {
            var isEnabled = false
            // Apparently querying the property can cause it to get updated
            // https://stackoverflow.com/a/55691449/11447682
            try {
                isEnabled = mNfcAdapter?.isEnabled ?: false
                if (!isEnabled) {
                    Log.v("NFC Check #1", "NFC is disabled. Checking again.")
                }
            } catch (_: Exception) {}
            try {
                isEnabled = mNfcAdapter?.isEnabled ?: false
                if (!isEnabled) {
                    Log.v("NFC Check #2", "NFC is disabled.")
                }
            } catch (_: Exception) {}
            if (isEnabled) {
                enableForegroundDispatch()
            } else {
                Log.w("NFC Check", "NFC is disabled - could be waiting on a system recovery")
            }
        } else {
            Log.e("NFC Check", "Adapter is completely unavailable!")
        }
    }

    private fun updateProgressBar(updated: Int) {
        if (mProgressBar == null) {
            mProgressBar = findViewById(R.id.nfcFlashProgressbar)
        }
        mProgressBar?.setProgress(updated, true)
    }

    private fun appendLog(tag: String, message: String) {
        val timestamp = DateFormat.format("HH:mm:ss", System.currentTimeMillis()).toString()
        val line = "[$timestamp] $message"
        if (mLogBuffer.size >= mMaxLogLines) {
            mLogBuffer.removeFirst()
        }
        mLogBuffer.addLast(line)
        Log.v(tag, message)
    }

    private fun showLogsDialog() {
        val logs = if (mLogBuffer.isEmpty()) {
            "No logs yet."
        } else {
            mLogBuffer.joinToString("\n")
        }
        AlertDialog.Builder(this)
            .setTitle("NFC Logs")
            .setMessage(logs)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun logTagDetails(tag: Tag) {
        try {
            val nfcA = NfcA.get(tag)
            if (nfcA != null) {
                appendLog("NfcA details", "atqa=${toHex(nfcA.atqa)} sak=${nfcA.sak}")
            }
        } catch (_: Exception) {}
        try {
            val isoDep = IsoDep.get(tag)
            if (isoDep != null) {
                appendLog(
                    "IsoDep details",
                    "historical=${toHex(isoDep.historicalBytes)} hiLayer=${toHex(isoDep.hiLayerResponse)}"
                )
            }
        } catch (_: Exception) {}
    }

    private fun toHex(bytes: ByteArray?): String {
        if (bytes == null) return "null"
        if (bytes.isEmpty()) return "empty"
        return bytes.joinToString("") { b -> "%02X".format(b.toInt() and 0xFF) }
    }
}
