package com.example

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.ConsoleMessage
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.MyApplicationTheme

class MainActivity : ComponentActivity() {

  private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

  private val filePickerLauncher =
    registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
      if (result.resultCode == Activity.RESULT_OK) {
        val intentData = result.data
        val uris = WebChromeClient.FileChooserParams.parseResult(result.resultCode, intentData)
        fileChooserCallback?.onReceiveValue(uris)
      } else {
        fileChooserCallback?.onReceiveValue(null)
      }
      fileChooserCallback = null
    }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    enableEdgeToEdge()

    setContent {
      MyApplicationTheme {
        var webViewInstance by remember { mutableStateOf<WebView?>(null) }

        BackHandler(enabled = webViewInstance?.canGoBack() == true) {
          webViewInstance?.goBack()
        }

        Box(
          modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0A0D12))
            .systemBarsPadding()
            .testTag("app_container")
        ) {
          AppWebView(
            onWebViewCreated = { webView ->
              webViewInstance = webView
            },
            onOpenFileChooser = { callback, params ->
              fileChooserCallback?.onReceiveValue(null)
              fileChooserCallback = callback
              try {
                val intent = try {
                  params.createIntent().apply {
                    type = "image/*"
                  }
                } catch (e: Exception) {
                  Intent(Intent.ACTION_GET_CONTENT).apply {
                    type = "image/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                  }
                }
                filePickerLauncher.launch(intent)
                true
              } catch (e: Exception) {
                fileChooserCallback = null
                false
              }
            }
          )
        }
      }
    }
  }

  @SuppressLint("SetJavaScriptEnabled")
  @Composable
  private fun AppWebView(
    onWebViewCreated: (WebView) -> Unit,
    onOpenFileChooser: (ValueCallback<Array<Uri>>, WebChromeClient.FileChooserParams) -> Boolean
  ) {
    AndroidView(
      modifier = Modifier
        .fillMaxSize()
        .testTag("main_web_view"),
      factory = { context ->
        WebView(context).apply {
          settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = true
            allowContentAccess = true
            allowFileAccessFromFileURLs = true
            allowUniversalAccessFromFileURLs = true
            mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            mediaPlaybackRequiresUserGesture = false
            cacheMode = WebSettings.LOAD_DEFAULT
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
            javaScriptCanOpenWindowsAutomatically = true
          }

          setBackgroundColor(android.graphics.Color.parseColor("#0A0D12"))
          setLayerType(android.view.View.LAYER_TYPE_SOFTWARE, null)

          // Expose native bridge to JavaScript for foolproof popup & ad launching
          addJavascriptInterface(WebAppBridge(this@MainActivity), "AndroidBridge")

          webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
              view: WebView?,
              detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
              return true
            }

            override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?
            ): Boolean {
              val url = request?.url?.toString() ?: return false
              if (url.startsWith("file:///android_asset/")) {
                return false
              }
              return try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                  addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                true
              } catch (e: Exception) {
                false
              }
            }
          }

          webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
              webView: WebView?,
              filePathCallback: ValueCallback<Array<Uri>>?,
              fileChooserParams: FileChooserParams?
            ): Boolean {
              if (filePathCallback != null && fileChooserParams != null) {
                return onOpenFileChooser(filePathCallback, fileChooserParams)
              }
              return false
            }

            override fun onCreateWindow(
              view: WebView?,
              isDialog: Boolean,
              isUserGesture: Boolean,
              resultMsg: android.os.Message?
            ): Boolean {
              val transport = resultMsg?.obj as? WebView.WebViewTransport
              val popupWebView = WebView(context).apply {
                webViewClient = object : WebViewClient() {
                  override fun shouldOverrideUrlLoading(
                    v: WebView?,
                    req: WebResourceRequest?
                  ): Boolean {
                    val targetUrl = req?.url?.toString() ?: return false
                    try {
                      val intent = Intent(Intent.ACTION_VIEW, Uri.parse(targetUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                      }
                      context.startActivity(intent)
                    } catch (_: Exception) {}
                    return true
                  }
                }
              }
              transport?.webView = popupWebView
              resultMsg?.sendToTarget()
              return true
            }

            override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
              android.util.Log.d("EarnDuoApp", "${consoleMessage?.message()} -- line ${consoleMessage?.lineNumber()}")
              return super.onConsoleMessage(consoleMessage)
            }
          }

          loadUrl("file:///android_asset/index.html")
          onWebViewCreated(this)
        }
      }
    )
  }

  class WebAppBridge(private val activity: Activity) {
    @JavascriptInterface
    fun openExternalUrl(url: String): Boolean {
      return try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
          addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        activity.startActivity(intent)
        true
      } catch (e: Exception) {
        false
      }
    }

    @JavascriptInterface
    fun isAndroidApp(): Boolean {
      return true
    }

    @JavascriptInterface
    fun dispatchTelegramApi(botToken: String, chatId: String, text: String, parseMode: String): String {
      return try {
        val url = java.net.URL("https://api.telegram.org/bot$botToken/sendMessage")
        val conn = (url.openConnection() as java.net.HttpURLConnection).apply {
          requestMethod = "POST"
          doOutput = true
          setRequestProperty("Content-Type", "application/json")
          connectTimeout = 15000
          readTimeout = 15000
        }
        val payload = org.json.JSONObject().apply {
          put("chat_id", chatId)
          put("text", text)
          put("parse_mode", parseMode)
        }.toString()
        conn.outputStream.use { os ->
          os.write(payload.toByteArray(Charsets.UTF_8))
        }
        val code = conn.responseCode
        if (code in 200..299) {
          "OK"
        } else {
          val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
          "ERROR: $err"
        }
      } catch (e: Exception) {
        "ERROR: ${e.message}"
      }
    }
  }
}
