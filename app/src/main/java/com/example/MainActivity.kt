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
  private var activeWebView: WebView? = null

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
              activeWebView = webView
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

  override fun onResume() {
    super.onResume()
    activeWebView?.onResume()
  }

  override fun onPause() {
    activeWebView?.onPause()
    super.onPause()
  }

  override fun onDestroy() {
    activeWebView?.destroy()
    activeWebView = null
    super.onDestroy()
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
          isFocusable = true
          isFocusableInTouchMode = true
          isClickable = true
          settings.builtInZoomControls = false
          settings.displayZoomControls = false

          // Expose native bridge to JavaScript for foolproof popup & ad launching
          addJavascriptInterface(WebAppBridge(this@MainActivity), "AndroidBridge")

          val configuredUrl = try {
            context.getString(R.string.web_app_url).trim()
          } catch (_: Exception) {
            ""
          }
          val isRemote = configuredUrl.isNotEmpty() && (configuredUrl.startsWith("http://") || configuredUrl.startsWith("https://"))
          val initialUrl = if (isRemote) {
            configuredUrl
          } else {
            "file:///android_asset/index.html"
          }

          // If connected to remote URL (e.g. GitHub Pages), ensure fresh content is fetched
          if (isRemote) {
            settings.cacheMode = WebSettings.LOAD_NO_CACHE
            clearCache(true)
          } else {
            settings.cacheMode = WebSettings.LOAD_DEFAULT
            clearCache(false)
          }

          webViewClient = object : WebViewClient() {
            override fun onRenderProcessGone(
              view: WebView?,
              detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
              return true
            }

            override fun onReceivedError(
              view: WebView?,
              request: WebResourceRequest?,
              error: android.webkit.WebResourceError?
            ) {
              super.onReceivedError(view, request, error)
              if (request?.isForMainFrame == true && initialUrl != "file:///android_asset/index.html") {
                android.util.Log.w("EarnDuoApp", "Remote URL failed, falling back to local asset: ${error?.description}")
                view?.loadUrl("file:///android_asset/index.html")
              }
            }

            override fun shouldOverrideUrlLoading(
              view: WebView?,
              request: WebResourceRequest?
            ): Boolean {
              val requestUri = request?.url ?: return false
              val url = requestUri.toString()
              if (url.startsWith("file:///android_asset/") || url.startsWith("data:") || url.startsWith("blob:")) {
                return false
              }

              // Allow navigation within the same host (e.g. GitHub Pages or app domain)
              val currentUrl = view?.url
              if (currentUrl != null) {
                val currentHost = Uri.parse(currentUrl).host
                val targetHost = requestUri.host
                if (currentHost != null && targetHost != null && currentHost.equals(targetHost, ignoreCase = true)) {
                  return false
                }
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

          loadUrl(initialUrl)
          onWebViewCreated(this)
        }
      }
    )
  }

  class WebAppBridge(private val activity: Activity) {
    @JavascriptInterface
    fun openExternalUrl(url: String): Boolean {
      return try {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isEmpty()) return false
        val uri = Uri.parse(trimmedUrl)
        
        activity.runOnUiThread {
          try {
            val intent = Intent(Intent.ACTION_VIEW, uri).apply {
              addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            activity.startActivity(intent)
          } catch (e: Exception) {
            try {
              val fallbackIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              }
              val chooser = Intent.createChooser(fallbackIntent, "Open Link")
              chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
              activity.startActivity(chooser)
            } catch (e2: Exception) {
              android.util.Log.e("EarnDuoApp", "Error opening URL: $trimmedUrl", e2)
            }
          }
        }
        true
      } catch (e: Exception) {
        android.util.Log.e("EarnDuoApp", "openExternalUrl error: $url", e)
        false
      }
    }

    @JavascriptInterface
    fun isAndroidApp(): Boolean {
      return true
    }
  }
}
