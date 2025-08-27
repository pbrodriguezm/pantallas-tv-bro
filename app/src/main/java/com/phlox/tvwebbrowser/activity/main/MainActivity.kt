package com.phlox.tvwebbrowser.activity.main

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.content.*
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.Uri
import android.os.*
import android.util.Log
import android.util.Patterns
import android.view.*
import android.view.animation.AccelerateInterpolator
import android.view.animation.Animation
import android.view.animation.AnimationUtils
import android.view.animation.DecelerateInterpolator
import android.webkit.*
import android.widget.FrameLayout
import android.widget.PopupMenu
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.os.postDelayed
import androidx.lifecycle.lifecycleScope
import com.phlox.tvwebbrowser.BuildConfig
import com.phlox.tvwebbrowser.Config
import com.phlox.tvwebbrowser.R
import com.phlox.tvwebbrowser.TVBro
import com.phlox.tvwebbrowser.activity.IncognitoModeMainActivity
import com.phlox.tvwebbrowser.activity.downloads.DownloadsActivity
import com.phlox.tvwebbrowser.activity.history.HistoryActivity
import com.phlox.tvwebbrowser.activity.main.dialogs.favorites.FavoriteEditorDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.favorites.FavoritesDialog
import com.phlox.tvwebbrowser.activity.main.dialogs.settings.SettingsDialog
import com.phlox.tvwebbrowser.activity.main.view.ActionBar
import com.phlox.tvwebbrowser.activity.main.view.tabs.TabsAdapter.Listener
import com.phlox.tvwebbrowser.databinding.ActivityMainBinding
import com.phlox.tvwebbrowser.model.*
import com.phlox.tvwebbrowser.service.downloads.DownloadService
import com.phlox.tvwebbrowser.singleton.AppDatabase
import com.phlox.tvwebbrowser.singleton.shortcuts.ShortcutMgr
import com.phlox.tvwebbrowser.utils.*
import com.phlox.tvwebbrowser.utils.activemodel.ActiveModelsRepository
import com.phlox.tvwebbrowser.webengine.WebEngine
import com.phlox.tvwebbrowser.webengine.WebEngineFactory
import com.phlox.tvwebbrowser.webengine.WebEngineWindowProviderCallback
import com.phlox.tvwebbrowser.webengine.gecko.GeckoWebEngine
import com.phlox.tvwebbrowser.widgets.NotificationView
import kotlinx.coroutines.*
import java.io.File
import java.io.InputStream
import java.io.UnsupportedEncodingException
import java.net.URL
import java.net.URLEncoder
import java.util.*
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine
import kotlin.system.exitProcess


open class MainActivity : AppCompatActivity(), ActionBar.Callback {
    companion object {
        private val TAG = MainActivity::class.java.simpleName
        const val VOICE_SEARCH_REQUEST_CODE = 10001
        const val MY_PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS = 10003
        const val MY_PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS = 10004
        const val PICK_FILE_REQUEST_CODE = 10005
        private const val REQUEST_CODE_HISTORY_ACTIVITY = 10006
        const val REQUEST_CODE_UNKNOWN_APP_SOURCES = 10007
        const val KEY_PROCESS_ID_TO_KILL = "proc_id_to_kill"
        private const val MY_PERMISSIONS_REQUEST_VOICE_SEARCH_PERMISSIONS = 10008
        private const val COMMON_REQUESTS_START_CODE = 10100
    }

    private lateinit var vb: ActivityMainBinding
    private lateinit var viewModel: MainActivityViewModel
    private lateinit var tabsModel: TabsModel
    private lateinit var settingsModel: SettingsModel
    private lateinit var adblockModel: AdblockModel
    private lateinit var autoUpdateModel: AutoUpdateModel
    private lateinit var uiHandler: Handler
    private var running: Boolean = false
    private var isFullscreen: Boolean = false
    private lateinit var prefs: SharedPreferences
    protected val config = TVBro.config
    private val voiceSearchHelper = VoiceSearchHelper(this, VOICE_SEARCH_REQUEST_CODE,
        MY_PERMISSIONS_REQUEST_VOICE_SEARCH_PERMISSIONS)
    private var lastCommonRequestsCode = COMMON_REQUESTS_START_CODE
    private var downloadService: DownloadService? = null
    private var downloadIntent: Download? = null
    var openUrlInExternalAppDialog: AlertDialog? = null
    private var linkActionsMenu: PopupMenu? = null

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val incognitoMode = config.incognitoMode
        Log.d(TAG, "onCreate incognitoMode: $incognitoMode")
        if (incognitoMode xor (this is IncognitoModeMainActivity)) {
            switchProcess(incognitoMode, intent?.extras)
            finish()
            return
        }
        val pidToKill = intent?.getIntExtra(KEY_PROCESS_ID_TO_KILL, -1) ?: -1
        if (pidToKill != -1) {
            Process.killProcess(pidToKill)
        }

        viewModel = ActiveModelsRepository.get(MainActivityViewModel::class, this)
        if (incognitoMode) {
            viewModel.prepareSwitchToIncognito()
        }
        settingsModel = ActiveModelsRepository.get(SettingsModel::class, this)
        adblockModel = ActiveModelsRepository.get(AdblockModel::class, this)
        tabsModel = ActiveModelsRepository.get(TabsModel::class, this)
        autoUpdateModel = ActiveModelsRepository.get(AutoUpdateModel::class, this)
        uiHandler = Handler()
        prefs = getSharedPreferences(TVBro.MAIN_PREFS_NAME, Context.MODE_PRIVATE)
        vb = ActivityMainBinding.inflate(layoutInflater)
        // evita que la pantalla se apague mientras la app esté visible
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        setContentView(vb.root)
        
        // modo inmersivo (oculta status y nav bars; se muestran con swipe y vuelven a ocultarse)
        val insets = WindowCompat.getInsetsController(window, window.decorView)
        insets.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        insets.hide(WindowInsetsCompat.Type.systemBars())

        

        vb.ivMiniatures.visibility = View.INVISIBLE
        vb.llBottomPanel.visibility = View.INVISIBLE
        vb.rlActionBar.visibility = View.INVISIBLE
        vb.progressBar.visibility = View.GONE

        vb.vTabs.listener = tabsListener

        vb.ibAdBlock.setOnClickListener { toggleAdBlockForTab() }
        vb.ibPopupBlock.setOnClickListener { lifecycleScope.launch(Dispatchers.Main) { showPopupBlockOptions() } }
        vb.ibHome.setOnClickListener { navigate(settingsModel.homePage) }
        vb.ibBack.setOnClickListener { navigateBack() }
        vb.ibForward.setOnClickListener {
            val tab = tabsModel.currentTab.value ?: return@setOnClickListener
            if (tab.webEngine.canGoForward()) {
                tab.webEngine.goForward()
            }
        }
        vb.ibRefresh.setOnClickListener { refresh() }
        vb.ibCloseTab.setOnClickListener { tabsModel.currentTab.value?.apply { closeTab(this) } }

        vb.vActionBar.callback = this

        vb.ibZoomIn.setOnClickListener {
            val tab = tabsModel.currentTab.value ?: return@setOnClickListener
            tab.webEngine.apply {
                if (this.canZoomIn()) {
                    tab.changingScale = true
                    this.zoomIn()
                }
                onWebViewUpdated(tab)
                if (config.isWebEngineGecko()) {
                    uiHandler.postDelayed({
                        vb.ibZoomIn.requestFocus()
                    }, 150)
                } else if (!this.canZoomIn()) {
                    vb.ibZoomOut.requestFocus()
                }
            }
        }
        vb.ibZoomOut.setOnClickListener {
            val tab = tabsModel.currentTab.value ?: return@setOnClickListener
            tab.webEngine.apply {
                if (this.canZoomOut()) {
                    tab.changingScale = true
                    this.zoomOut()
                }
                onWebViewUpdated(tab)
                if (config.isWebEngineGecko()) {
                    uiHandler.postDelayed({
                        vb.ibZoomOut.requestFocus()
                    }, 150)
                } else if (!this.canZoomOut()) {
                    vb.ibZoomIn.requestFocus()
                }
            }
        }

        vb.llBottomPanel.childs.forEach {
            it.setOnTouchListener(bottomButtonsOnTouchListener)
            it.onFocusChangeListener = bottomButtonsFocusListener
            it.setOnKeyListener(bottomButtonsKeyListener)
        }

        config.userAgentString.subscribe(this.lifecycle, false) {
            for (tab in tabsModel.tabsStates) {
                tab.webEngine.userAgentString = it
            }
        }

        config.theme.subscribe(this.lifecycle, false) {
            when (it) {
                Config.Theme.BLACK -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                Config.Theme.WHITE -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                else -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
            }
            WebEngineFactory.onThemeSettingUpdated(it)
        }

        settingsModel.keepScreenOn.subscribe(this.lifecycle) {
            if (it) {
                window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            } else {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
        }

        viewModel.homePageLinks.subscribe(this) {
            Log.i(TAG, "homePageLinks updated")
            val currentUrl = tabsModel.currentTab.value?.url ?: return@subscribe
            if (Config.HOME_PAGE_URL == currentUrl) {
                tabsModel.currentTab.value?.webEngine?.reload()
            }
        }

        tabsModel.currentTab.subscribe(this) {
            vb.vActionBar.setAddressBoxText(it?.url ?: "")
            it?.let {
                onWebViewUpdated(it)
            }
        }

        tabsModel.tabsStates.subscribe(this, false) {
            if (it.isEmpty()) {
                if (!config.isWebEngineGecko()) {
                    vb.flWebViewContainer.removeAllViews()
                }
            }
        }

        loadState()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            val insets = WindowCompat.getInsetsController(window, window.decorView)
            insets.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            insets.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    
    private var progressBarHideRunnable: Runnable = Runnable {
        val anim = AnimationUtils.loadAnimation(this@MainActivity, android.R.anim.fade_out)
        anim.setAnimationListener(object : BaseAnimationListener() {
            override fun onAnimationEnd(animation: Animation) {
                vb.progressBar.visibility = View.GONE
            }
        })
        vb.progressBar.startAnimation(anim)
    }

    private val mConnectivityChangeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val activeNetwork = cm.activeNetworkInfo
            val isConnected = activeNetwork != null && activeNetwork.isConnectedOrConnecting
            val tab = tabsModel.currentTab.value ?: return
            tab.webEngine.setNetworkAvailable(isConnected)
        }
    }

    private val displayThumbnailRunnable = object : Runnable {
        var tabState: WebTabState? = null
        override fun run() {
            tabState?.let {
                lifecycleScope.launch(Dispatchers.Main) {
                    displayThumbnail(it)
                }
            }
        }
    }

    private val tabsListener = object : Listener {
        override fun onTitleChanged(index: Int) {
            Log.d(TAG, "onTitleChanged: $index")
            val tab = tabByTitleIndex(index)
            vb.vActionBar.setAddressBoxText(tab?.url ?: "")
            uiHandler.removeCallbacks(displayThumbnailRunnable)
            displayThumbnailRunnable.tabState = tab
            uiHandler.postDelayed(displayThumbnailRunnable, 200)
        }

        override fun onTitleSelected(index: Int) {
            syncTabWithTitles()
            hideMenuOverlay()
        }

        override fun onAddNewTabSelected() {
            openInNewTab(settingsModel.homePage, tabsModel.tabsStates.size)
        }

        override fun closeTab(tabState: WebTabState?) = this@MainActivity.closeTab(tabState)

        override fun openInNewTab(url: String, tabIndex: Int) {
            this@MainActivity.openInNewTab(url, tabIndex,
                needToHideMenuOverlay = false,
                navigateImmediately = true
            )
        }
    }

    override fun closeWindow() {
        Log.d(TAG, "closeWindow")
        lifecycleScope.launch {
            if (config.incognitoMode) {
                toggleIncognitoMode(false).join()
            }
            finish()
        }
    }

    override fun showDownloads() {
        startActivity(Intent(this@MainActivity, DownloadsActivity::class.java))
    }

    override fun showHistory() {
        startActivityForResult(
                Intent(this@MainActivity, HistoryActivity::class.java),
                REQUEST_CODE_HISTORY_ACTIVITY)
        hideMenuOverlay()
    }

    override fun showFavorites() {
        val currentTab = tabsModel.currentTab.value
        val currentPageTitle = currentTab?.title ?: ""
        val currentPageUrl = currentTab?.url ?: ""

        FavoritesDialog(this@MainActivity, lifecycleScope, object : FavoritesDialog.Callback {
            override fun onFavoriteChoosen(item: FavoriteItem?) {
                navigate(item!!.url!!)
            }
        }, currentPageTitle, currentPageUrl).show()
        hideMenuOverlay()
    }

    private val bottomButtonsOnTouchListener = View.OnTouchListener{ v, e ->
        when (e.action) {
            MotionEvent.ACTION_DOWN -> {
                return@OnTouchListener true
            }
            MotionEvent.ACTION_UP -> {
                hideMenuOverlay(false)
                v.performClick()
                return@OnTouchListener true
            }
            else -> return@OnTouchListener false
        }
    }

    private val bottomButtonsFocusListener = View.OnFocusChangeListener { view, hasFocus ->
        if (hasFocus) {
            hideMenuOverlay(false)
        }
    }

    private val bottomButtonsKeyListener = View.OnKeyListener { view, i, keyEvent ->
        when (keyEvent.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                if (keyEvent.action == KeyEvent.ACTION_UP) {
                    hideBottomPanel()
                    tabsModel.currentTab.value?.webEngine?.getView()?.requestFocus()
                }
                return@OnKeyListener true
            }
        }
        false
    }

    private fun tabByTitleIndex(index: Int) =
            if (index >= 0 && index < tabsModel.tabsStates.size) tabsModel.tabsStates[index] else null

    override fun showSettings() {
        SettingsDialog(this, settingsModel).show()
    }

    override fun onExtendedAddressBarMode() {
        vb.llBottomPanel.visibility = View.INVISIBLE
    }

    override fun onUrlInputDone() {
        hideMenuOverlay()
    }

    fun navigateBack(goHomeIfNoHistory: Boolean = false) {
        val currentTab = tabsModel.currentTab.value
        if (currentTab != null && currentTab.webEngine.canGoBack()) {
            currentTab.webEngine.goBack()
        } else if (goHomeIfNoHistory) {
            navigate(settingsModel.homePage)
        } else if (vb.rlActionBar.visibility != View.VISIBLE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    fun refresh() {
        tabsModel.currentTab.value?.webEngine?.reload()
    }

    override fun onDestroy() {
        Log.d(TAG, "onDestroy")
        //here properties can be uninitialized in case of wrong activity for incognito mode
        //detection and force activity restart in onCreate()
        if (::tabsModel.isInitialized) {
            tabsModel.onDetachActivity()
        }
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        val intentUri = intent.data
        if (intentUri != null) {
            openInNewTab(intentUri.toString(), tabsModel.tabsStates.size,
                needToHideMenuOverlay = true,
                navigateImmediately = true
            )
        }
    }

    private fun loadState() = lifecycleScope.launch(Dispatchers.Main) {
        WebEngineFactory.initialize(this@MainActivity, vb.flWebViewContainer)

        vb.progressBarGeneric.visibility = View.VISIBLE
        vb.progressBarGeneric.requestFocus()
        viewModel.loadState().join()
        tabsModel.loadState().join()

        if (!running) {
            return@launch
        }

        vb.progressBarGeneric.visibility = View.GONE

        val intentUri = intent.data
        if (intentUri == null) {
            if (tabsModel.tabsStates.isEmpty()) {
                openInNewTab(settingsModel.homePage, 0,
                    needToHideMenuOverlay = true,
                    navigateImmediately = true
                )
            } else {
                var foundSelectedTab = false
                for (i in tabsModel.tabsStates.indices) {
                    val tab = tabsModel.tabsStates[i]
                    if (tab.selected) {
                        changeTab(tab)
                        foundSelectedTab = true
                        break
                    }
                }
                if (!foundSelectedTab) {//this may happen in some error states
                    changeTab(tabsModel.tabsStates[0])
                }
            }
        } else {
            openInNewTab(intentUri.toString(), tabsModel.tabsStates.size, needToHideMenuOverlay = true,
                navigateImmediately = true)
        }

        val currentTab = tabsModel.currentTab.value
        if (currentTab == null || currentTab.url == settingsModel.homePage) {
            showMenuOverlay()
        }
        if (autoUpdateModel.needAutoCheckUpdates &&
            autoUpdateModel.updateChecker.versionCheckResult == null &&
                !autoUpdateModel.lastUpdateNotificationTime.sameDay(Calendar.getInstance())) {
            autoUpdateModel.checkUpdate(false){
                if (autoUpdateModel.updateChecker.hasUpdate()) {
                    autoUpdateModel.showUpdateDialogIfNeeded(this@MainActivity)
                }
            }
        }
    }

    private fun openInNewTab(url: String?, index: Int = 0, needToHideMenuOverlay: Boolean = true, navigateImmediately: Boolean): WebEngine? {
        if (url == null) {
            return null
        }
        val tab = WebTabState(url = url, incognito = config.incognitoMode)
        createWebView(tab) ?: return null
        tabsModel.tabsStates.add(index, tab)
        changeTab(tab)
        if (navigateImmediately) {
            navigate(url)
        }
        if (needToHideMenuOverlay && vb.rlActionBar.visibility == View.VISIBLE) {
            hideMenuOverlay(true)
        }
        return tab.webEngine
    }

    private fun closeTab(tab: WebTabState?) {
        if (tab == null) return
        val position = tabsModel.tabsStates.indexOf(tab)
        if (tabsModel.currentTab.value == tab) {
            tabsModel.currentTab.value = null
        }
        when {
            tabsModel.tabsStates.size == 1 -> openInNewTab(settingsModel.homePage, 0, needToHideMenuOverlay = true, navigateImmediately = true)

            position > 0 -> changeTab(tabsModel.tabsStates[position - 1])

            else -> changeTab(tabsModel.tabsStates[position + 1])
        }
        tabsModel.onCloseTab(tab)
        hideMenuOverlay(true)
        hideBottomPanel()
    }

    private fun changeTab(newTab: WebTabState) {
        tabsModel.changeTab(newTab, { tab: WebTabState -> createWebView(tab) }, vb.flWebViewContainer, vb.flFullscreenContainer, WebEngineCallback(newTab))
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(tab: WebTabState): View? {
        val webView: View
        try {
            webView = tab.webEngine.getOrCreateView(this) //WebViewEx(this, WebViewCallback(tab), AndroidJSInterface(this, viewModel, tabsModel, tab))
        } catch (e: Throwable) {
            e.printStackTrace()

            if (!config.isWebEngineGecko()) {
                val dialogBuilder = AlertDialog.Builder(this)
                    .setTitle(R.string.error)
                    .setCancelable(false)
                    .setMessage(R.string.err_webview_can_not_link)
                    .setNegativeButton(R.string.exit) { _, _ -> finish() }

                val appPackageName = "com.google.android.webview"
                val intent =
                    Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appPackageName"))
                val activities = packageManager.queryIntentActivities(intent, 0)
                if (activities.size > 0) {
                    dialogBuilder.setPositiveButton(R.string.find_in_apps_store) { _, _ ->
                        try {
                            startActivity(intent)
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        finish()
                    }
                }
                dialogBuilder.show()
            }
            return null
        }

        var ua = config.userAgentString.value
        if (ua?.contains("TV Bro/1.0 ") == true) {//legacy ua string - now default one should be used
            config.userAgentString.value = null
            ua = null
        }
        if (ua != null) {
            tab.webEngine.userAgentString = ua
        }

        return webView
    }

    private fun onWebViewUpdated(tab: WebTabState) {
        vb.ibBack.isEnabled = tab.webEngine.canGoBack() == true
        vb.ibForward.isEnabled = tab.webEngine.canGoForward() == true
        val zoomPossible = tab.webEngine.canZoomIn() || tab.webEngine.canZoomOut()
        vb.ibZoomIn.visibility = if (zoomPossible) View.VISIBLE else View.GONE
        vb.ibZoomOut.visibility = if (zoomPossible) View.VISIBLE else View.GONE
        vb.ibZoomIn.isEnabled = tab.webEngine.canZoomIn() == true
        vb.ibZoomOut.isEnabled = tab.webEngine.canZoomOut() == true

        val adblockEnabled = tab.adblock ?: config.adBlockEnabled
        vb.ibAdBlock.setImageResource(if (adblockEnabled) R.drawable.ic_adblock_on else R.drawable.ic_adblock_off)
        vb.tvBlockedAdCounter.visibility = if (adblockEnabled && tab.blockedAds != 0) View.VISIBLE else View.GONE
        vb.tvBlockedAdCounter.text = tab.blockedAds.toString()

        vb.tvBlockedPopupCounter.visibility = if (tab.blockedPopups != 0) View.VISIBLE else View.GONE
        vb.tvBlockedPopupCounter.text = tab.blockedPopups.toString()
    }

    private fun onDownloadRequested(url: String, referer: String, originalDownloadFileName: String, userAgent: String?, mimeType: String? = null,
                            operationAfterDownload: Download.OperationAfterDownload = Download.OperationAfterDownload.NOP,
                            base64BlobData: String? = null, stream: InputStream?, size: Long = 0L) {
        downloadIntent = Download(url, originalDownloadFileName, null, operationAfterDownload,
            mimeType, referer, userAgent, base64BlobData, stream, size)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R &&
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                MY_PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS
            )
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                MY_PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS
            )
        } else {
            startDownload()
        }
    }

    private fun startDownload() {
        val download = this.downloadIntent ?: return
        this.downloadIntent = null
        downloadService?.startDownload(download)
        onDownloadStarted(download.filename)
    }

    override fun onTrimMemory(level: Int) {
        for (tab in tabsModel.tabsStates) {
            if (!tab.selected) {
                tab.trimMemory()
            }
        }
        super.onTrimMemory(level)
    }

    override fun onRequestPermissionsResult(requestCode: Int,
                                            permissions: Array<String>, grantResults: IntArray) {
        if (voiceSearchHelper.processPermissionsResult(requestCode, permissions, grantResults)) {
            return
        }
        if (tabsModel.currentTab.value?.webEngine?.onPermissionsResult(requestCode, permissions, grantResults) == true) return
        if (grantResults.isEmpty()) return
        when (requestCode) {
            MY_PERMISSIONS_REQUEST_EXTERNAL_STORAGE_ACCESS -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startDownload()
                }
            }
            MY_PERMISSIONS_REQUEST_POST_NOTIFICATIONS_ACCESS -> {
                if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    startDownload()
                }
            }
            else -> {
                super.onRequestPermissionsResult(requestCode, permissions, grantResults)
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (voiceSearchHelper.processActivityResult(requestCode, resultCode, data)) {
            return
        }
        when (requestCode) {
            PICK_FILE_REQUEST_CODE -> {
                tabsModel.currentTab.value?.webEngine?.onFilePicked(resultCode, data)
            }
            REQUEST_CODE_HISTORY_ACTIVITY -> if (resultCode == Activity.RESULT_OK) {
                val url = data?.getStringExtra(HistoryActivity.KEY_URL)
                if (url != null) {
                    navigate(url)
                }
                hideMenuOverlay()
            }
            REQUEST_CODE_UNKNOWN_APP_SOURCES -> if (autoUpdateModel.needToShowUpdateDlgAgain) {
                autoUpdateModel.showUpdateDialogIfNeeded(this)
            }

            else -> super.onActivityResult(requestCode, resultCode, data)
        }
    }

    override fun onStart() {
        super.onStart()
        bindService(Intent(this, DownloadService::class.java), downloadServiceConnection, Context.BIND_AUTO_CREATE)
    }

    override fun onStop() {
        super.onStop()
        unbindService(downloadServiceConnection)
    }

    override fun onResume() {
        running = true
        super.onResume()
        val intentFilter = IntentFilter("android.net.conn.CONNECTIVITY_CHANGE")
        registerReceiver(mConnectivityChangeReceiver, intentFilter)
        tabsModel.currentTab.value?.webEngine?.onResume()
    }

    override fun onPause() {
        unregisterReceiver(mConnectivityChangeReceiver)
        tabsModel.currentTab.value?.apply {
            webEngine.onPause()
            onPause()
            tabsModel.saveTab(this)
        }

        super.onPause()
        running = false
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
    }

    private fun toggleAdBlockForTab() {
        tabsModel.currentTab.value?.apply {
            val currentState = adblock ?: config.adBlockEnabled
            val newState = !currentState
            adblock = newState
            webEngine.onUpdateAdblockSetting(newState)
            onWebViewUpdated(this)
            refresh()
        }
    }

    private suspend fun showPopupBlockOptions() {
        val tab = tabsModel.currentTab.value ?: return
        val currentHostConfig = tab.findHostConfig(false)
        val currentBlockPopupsLevelValue = currentHostConfig?.popupBlockLevel ?: HostConfig.DEFAULT_BLOCK_POPUPS_VALUE
        val hostName = currentHostConfig?.hostName ?: try { URL(tab.url).host } catch (e: Exception) { "" }
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.block_popups_s, hostName))
            .setSingleChoiceItems(R.array.popup_blocking_level, currentBlockPopupsLevelValue) {
                    dialog, itemId -> lifecycleScope.launch {
                        tab.changePopupBlockingLevel(itemId)
                        dialog.dismiss()
                    }
            }
            .show()
    }

    fun navigate(url: String) {
        vb.vActionBar.setAddressBoxTextColor(ContextCompat.getColor(this@MainActivity, R.color.default_url_color))
        val tab = tabsModel.currentTab.value
        if (tab != null) {
            tab.url = url
            tab.webEngine.loadUrl(url)
        } else {
            openInNewTab(url, 0, needToHideMenuOverlay = true, navigateImmediately = true)
        }
    }

    override fun search(aText: String) {
        var text = aText
        val trimmedLowercased = text.trim { it <= ' ' }.lowercase(Locale.ROOT)
        if (Patterns.WEB_URL.matcher(text).matches() || trimmedLowercased.startsWith("http://") || trimmedLowercased.startsWith("https://")) {
            if (!text.lowercase(Locale.ROOT).contains("://")) {
                text = "https://$text"
            }
            navigate(text)
        } else {
            var query: String? = null
            try {
                query = URLEncoder.encode(text, "utf-8")
            } catch (e1: UnsupportedEncodingException) {
                e1.printStackTrace()
                Utils.showToast(this, R.string.error)
                return
            }

            val searchUrl = config.searchEngineURL.value.replace("[query]", query!!)
            navigate(searchUrl)
        }
    }

    override fun toggleIncognitoMode() {
        toggleIncognitoMode(true)
    }

    private fun toggleIncognitoMode(andSwitchProcess: Boolean) = lifecycleScope.launch(Dispatchers.Main) {
        Log.d(TAG, "toggleIncognitoMode andSwitchProcess: $andSwitchProcess")
        val becomingIncognitoMode = !config.incognitoMode
        vb.progressBarGeneric.visibility = View.VISIBLE
        if (!becomingIncognitoMode) {
            if (!config.isWebEngineGecko()) {
                withContext(Dispatchers.IO) {
                    WebStorage.getInstance().deleteAllData()
                    CookieManager.getInstance().removeAllCookies(null)
                    CookieManager.getInstance().flush()
                }

                WebEngineFactory.clearCache(this@MainActivity)
            }

            tabsModel.onCloseAllTabs().join()
            tabsModel.currentTab.value = null

            if (!config.isWebEngineGecko()) {
                viewModel.clearIncognitoData().join()
            }
        }
        vb.progressBarGeneric.visibility = View.GONE
        config.incognitoMode = becomingIncognitoMode
        if (andSwitchProcess) {
            switchProcess(becomingIncognitoMode)
        }
    }

    private fun switchProcess(incognitoMode: Boolean, intentDataToCopy: Bundle? = null) {
        Log.d(TAG, "switchProcess incognitoMode: $incognitoMode")
        val activityClass = if (incognitoMode) IncognitoModeMainActivity::class.java
        else MainActivity::class.java
        val intent = Intent(this@MainActivity, activityClass)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        intent.putExtra(KEY_PROCESS_ID_TO_KILL, Process.myPid())
        intentDataToCopy?.let {
            intent.putExtras(it)
        }
        startActivity(intent)
        exitProcess(0)
    }

    fun toggleMenu() {
        if (vb.rlActionBar.visibility == View.INVISIBLE) {
            showMenuOverlay()
        } else {
            hideMenuOverlay()
        }
    }

    @SuppressLint("RestrictedApi")
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val shortcutMgr = ShortcutMgr.getInstance()
        val keyCode = if (event.keyCode != 0) event.keyCode else event.scanCode

        if (keyCode == KeyEvent.KEYCODE_BACK && isFullscreen) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post {
                    tabsModel.currentTab.value?.webEngine?.hideFullscreenView()
                }
            }
            return true
        } else if (keyCode == KeyEvent.KEYCODE_BACK && vb.llBottomPanel.visibility == View.VISIBLE && vb.rlActionBar.visibility != View.VISIBLE) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { hideBottomPanel() }
            }
            return true
        } else if (keyCode == KeyEvent.KEYCODE_BACK && vb.flWebViewContainer.fingerMode) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { vb.flWebViewContainer.exitFingerMode() }
            }
            return true
        } else if (shortcutMgr.canProcessKeyCode(keyCode)) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post { shortcutMgr.process(keyCode, this) }
            }
            return true
        } else if ((keyCode == KeyEvent.KEYCODE_MEDIA_PLAY ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PAUSE ||
            keyCode == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
            if (event.action == KeyEvent.ACTION_UP) {
                uiHandler.post {
                    tabsModel.currentTab.value?.webEngine?.togglePlayback()
                }
            }
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    private fun showMenuOverlay() {
        vb.ivMiniatures.visibility = View.VISIBLE
        vb.llBottomPanel.visibility = View.VISIBLE
        vb.flWebViewContainer.visibility = View.INVISIBLE
        val currentTab = tabsModel.currentTab.value
        if (currentTab != null) {
            lifecycleScope.launch {
                currentTab.thumbnail = currentTab.webEngine.renderThumbnail(currentTab.thumbnail)
                displayThumbnail(currentTab)
            }
        }

        vb.llBottomPanel.translationY = vb.llBottomPanel.height.toFloat()
        vb.llBottomPanel.alpha = 0f
        vb.llBottomPanel.animate()
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .translationY(0f)
                .alpha(1f)
                .withEndAction {
                    vb.vActionBar.catchFocus()
                }
                .start()

        vb.vActionBar.dismissExtendedAddressBarMode()

        vb.rlActionBar.visibility = View.VISIBLE
        vb.rlActionBar.translationY = -vb.rlActionBar.height.toFloat()
        vb.rlActionBar.alpha = 0f
        vb.rlActionBar.animate()
                .translationY(0f)
                .alpha(1f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()

        vb.ivMiniatures.layoutParams = vb.ivMiniatures.layoutParams.apply { this.height = vb.flWebViewContainer.height }
        vb.ivMiniatures.translationY = 0f
        vb.ivMiniatures.animate()
                .translationY(vb.rlActionBar.height.toFloat())
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .start()
    }

    private suspend fun displayThumbnail(currentTab: WebTabState?) {
        if (currentTab != null) {
            if (tabByTitleIndex(vb.vTabs.current) != currentTab) return
            vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
            vb.ivMiniatures.visibility = View.VISIBLE
            if (currentTab.thumbnail != null) {
                vb.ivMiniatures.setImageBitmap(currentTab.thumbnail)
            } else if (currentTab.thumbnailHash != null) {
                withContext(Dispatchers.IO) {
                    val thumbnail = currentTab.loadThumbnail()
                    withContext(Dispatchers.Main) {
                        if (thumbnail != null) {
                            vb.ivMiniatures.setImageBitmap(currentTab.thumbnail)
                        } else {
                            vb.ivMiniatures.setImageResource(0)
                        }
                    }
                }
            } else {
                vb.ivMiniatures.setImageResource(0)
            }
        } else {
            vb.llMiniaturePlaceholder.visibility = View.VISIBLE
            vb.ivMiniatures.setImageResource(0)
            vb.ivMiniatures.visibility = View.INVISIBLE
        }
    }

    private fun hideMenuOverlay(hideBottomButtons: Boolean = true) {
        if (vb.rlActionBar.visibility == View.INVISIBLE) {
            return
        }
        if (hideBottomButtons) {
            hideBottomPanel()
        }

        vb.rlActionBar.animate()
                .translationY(-vb.rlActionBar.height.toFloat())
                .alpha(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    vb.rlActionBar.visibility = View.INVISIBLE
                }
                .start()

        if (vb.llMiniaturePlaceholder.visibility == View.VISIBLE) {
            vb.llMiniaturePlaceholder.visibility = View.INVISIBLE
            vb.ivMiniatures.visibility = View.VISIBLE
        }

        vb.ivMiniatures.translationY = vb.rlActionBar.height.toFloat()
        vb.ivMiniatures.animate()
                .translationY(0f)
                .setDuration(300)
                .setInterpolator(DecelerateInterpolator())
                .withEndAction {
                    vb.ivMiniatures.visibility = View.INVISIBLE
                    vb.rlActionBar.visibility = View.INVISIBLE
                    vb.ivMiniatures.setImageResource(0)
                    syncTabWithTitles()
                    vb.flWebViewContainer.visibility = View.VISIBLE
                    if (hideBottomButtons) {
                        tabsModel.currentTab.value?.webEngine?.getView()?.requestFocus()
                    }
                }
                .start()
    }

    private fun syncTabWithTitles() {
        val tab = tabByTitleIndex(vb.vTabs.current)
        if (tab == null) {
            openInNewTab(settingsModel.homePage, if (vb.vTabs.current < 0) 0 else tabsModel.tabsStates.size,
                needToHideMenuOverlay = true,
                navigateImmediately = true
            )
        } else if (!tab.selected) {
            changeTab(tab)
        }
    }

    private fun hideBottomPanel() {
        if (vb.llBottomPanel.visibility != View.VISIBLE) return
        vb.llBottomPanel.animate()
                .setDuration(300)
                .setInterpolator(AccelerateInterpolator())
                .translationY(vb.llBottomPanel.height.toFloat())
                .withEndAction {
                    vb.llBottomPanel.translationY = 0f
                    vb.llBottomPanel.visibility = View.INVISIBLE
                }
                .start()
    }

    private fun onDownloadStarted(fileName: String) {
        Utils.showToast(this, getString(R.string.download_started,
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).toString() + File.separator + fileName))
        showMenuOverlay()
    }

    override fun initiateVoiceSearch() {
        hideMenuOverlay()
        voiceSearchHelper.initiateVoiceSearch(object : VoiceSearchHelper.Callback {
            override fun onResult(text: String?) {
                if (text == null) {
                    Utils.showToast(this@MainActivity, getString(R.string.can_not_recognize))
                    return
                }
                search(text)
                hideMenuOverlay()
            }
        })
    }

    private fun onEditHomePageBookmark(favoriteItem: FavoriteItem) {
        FavoriteEditorDialog(this, object : FavoriteEditorDialog.Callback {
            override fun onDone(item: FavoriteItem) {
                viewModel.onHomePageLinkEdited(item)
            }
        }, favoriteItem).show()
    }

    private inner class WebEngineCallback(val tab: WebTabState) : WebEngineWindowProviderCallback {
        override fun getActivity(): Activity {
            return this@MainActivity
        }

        override fun onOpenInNewTabRequested(url: String, navigateImmediately: Boolean): WebEngine? {
            var index = tabsModel.tabsStates.indexOf(tabsModel.currentTab.value)
            index = if (index == -1) tabsModel.tabsStates.size else index + 1
            return openInNewTab(url, index, true, navigateImmediately)
        }

        override fun onDownloadRequested(url: String) {
            Log.i(TAG, "onDownloadRequested url: $url")
            val fileName = Uri.parse(url).lastPathSegment
            val mimeType = MimeTypeMap.getSingleton().getMimeTypeFromExtension(MimeTypeMap.getFileExtensionFromUrl(url))
            onDownloadRequested(url, tab.url,
                fileName ?: "url.html", tab.webEngine.userAgentString, mimeType)
        }

        override fun onDownloadRequested(url: String, referer: String,
            originalDownloadFileName: String, userAgent: String?, mimeType: String?,
            operationAfterDownload: Download.OperationAfterDownload, base64BlobData: String?,
                                         stream: InputStream?, size: Long) {
            this@MainActivity.onDownloadRequested(url, referer, originalDownloadFileName,
                userAgent, mimeType, operationAfterDownload, base64BlobData, stream, size)
        }

        override fun onDownloadRequested(url: String, userAgent: String?, contentDisposition: String,
                                         mimetype: String?, contentLength: Long ) {
            Log.i(TAG, "DownloadListener.onDownloadStart url: $url")
            onDownloadRequested(url, tab.url,
                DownloadUtils.guessFileName(url, contentDisposition, mimetype), userAgent, mimetype)
        }

        override fun onProgressChanged(newProgress: Int) {
            vb.progressBar.visibility = View.VISIBLE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                vb.progressBar.setProgress(newProgress, true)
            } else {
                vb.progressBar.progress = newProgress
            }
            uiHandler.removeCallbacks(progressBarHideRunnable)
            if (newProgress == 100) {
                uiHandler.postDelayed(progressBarHideRunnable, 1000)
            } else {
                uiHandler.postDelayed(progressBarHideRunnable, 5000)
            }
        }

        override fun onReceivedTitle(title: String) {
            tab.title = title
            vb.vTabs.onTabTitleUpdated(tab)
            viewModel.onTabTitleUpdated(tab)
        }

        override fun requestPermissions(array: Array<String>): Int {
            val requestCode = lastCommonRequestsCode++
            this@MainActivity.requestPermissions(array, requestCode)
            return requestCode
        }

        override fun onShowFileChooser(intent: Intent): Boolean {
            try {
                startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
            } catch (e: ActivityNotFoundException) {
                try {
                    //trying again with type */* (seems file pickers usually doesn't support specific types in intent filters but still can do the job)
                    intent.type = "*/*"
                    startActivityForResult(intent, PICK_FILE_REQUEST_CODE)
                } catch (e: ActivityNotFoundException) {
                    Utils.showToast(applicationContext, getString(R.string.err_cant_open_file_chooser))
                    return false
                }
            }
            return true
        }

        override fun onReceivedIcon(icon: Bitmap) {
            vb.vTabs.onFavIconUpdated(tab)
        }

        override fun shouldOverrideUrlLoading(url: String): Boolean {
            tab.lastLoadingUrl = url

            val uri = try {
                Uri.parse(url)
            } catch (e: Exception) {
                Log.e(TAG, "shouldOverrideUrlLoading: ", e)
                return true
            }

            if (uri.scheme == null) {
                Log.d(TAG, "shouldOverrideUrlLoading: no scheme: $url")
                return true
            }

            if (URLUtil.isNetworkUrl(url) || uri.scheme.equals("javascript", true) ||
                    uri.scheme.equals("data", true) || uri.scheme.equals("about", true) ||
                    uri.scheme.equals("blob", true)) {
                Log.d(TAG, "shouldOverrideUrlLoading: network url: $url")
                return false
            }

            if (uri.scheme.equals("intent", true)) {
                Log.d(TAG, "shouldOverrideUrlLoading: intent url: $url")
                onOpenInExternalAppRequested(url)
                return true
            }

            //try to handle intent for non-network urls by external apps
            //TODO: ask user if he wants to open this url in external app
            return try {
                Log.d(TAG, "shouldOverrideUrlLoading: non-network url: $url")
                val intent = Intent(Intent.ACTION_VIEW, uri)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                if (intent.resolveActivity(TVBro.instance.packageManager) != null) {
                    runOnUiThread {
                        askUserAndOpenInExternalApp(url, intent)
                    }
                    true
                } else {
                    Log.d(TAG, "shouldOverrideUrlLoading: no activity to handle intent")
                    runOnUiThread {
                        Utils.showToast(applicationContext, getString(R.string.err_no_app_to_handle_url))
                    }
                    false
                }
            } catch (e: Exception) {
                Log.e(TAG, "shouldOverrideUrlLoading: ", e)
                true
            }
        }

        override fun onPageStarted(url: String?) {
            onWebViewUpdated(tab)
            val webViewUrl = tab.webEngine.url
            if (webViewUrl != null) {
                tab.url = webViewUrl
            } else if (url != null) {
                tab.url = url
            }
            if (tabByTitleIndex(vb.vTabs.current) == tab) {
                vb.vActionBar.setAddressBoxText(tab.url)
            }
            tab.blockedAds = 0
            tab.blockedPopups = 0
        }

        override fun onPageFinished(url: String?) {
            if (tabsModel.currentTab.value == null) {
                return
            }
            onWebViewUpdated(tab)

            val webViewUrl = tab.webEngine.url
            if (webViewUrl != null) {
                tab.url = webViewUrl
            } else if (url != null) {
                tab.url = url
            }
            if (tabByTitleIndex(vb.vTabs.current) == tab) {
                vb.vActionBar.setAddressBoxText(tab.url)
            }

            //thumbnail
            tabsModel.tabsStates.onEach { if (it != tab) it.thumbnail = null }
            lifecycleScope.launch {
                val newThumbnail = tab.webEngine.renderThumbnail(tab.thumbnail)
                if (newThumbnail != null) {
                    tab.updateThumbnail(this@MainActivity, newThumbnail)
                    if (vb.rlActionBar.visibility == View.VISIBLE && tab == tabsModel.currentTab.value) {
                        displayThumbnail(tab)
                    }
                }
            }
        }

        override fun onPageCertificateError(url: String?) {
            vb.vActionBar.setAddressBoxTextColor(Color.RED)
        }

        override fun isAd(url: Uri, acceptHeader: String?, baseUri: Uri): Boolean? {
            return adblockModel.isAd(url, acceptHeader, baseUri)
        }

        override fun isAdBlockingEnabled(): Boolean {
            tabsModel.currentTab.value?.adblock?.apply {
                return this
            }
            return  config.adBlockEnabled
        }

        override fun isDialogsBlockingEnabled(): Boolean {
            if (tab.url == Config.HOME_PAGE_URL) return false
            return runBlocking(Dispatchers.Main.immediate) { tab.shouldBlockNewWindow(true, false) }
        }

        override fun onBlockedAd(uri: String) {
            Log.i(TAG, "onBlockedAd: $uri")
            if (!config.adBlockEnabled) return
            tab.blockedAds++
            vb.tvBlockedAdCounter.visibility = if (tab.blockedAds > 0) View.VISIBLE else View.GONE
            vb.tvBlockedAdCounter.text = tab.blockedAds.toString()
        }

        override fun onBlockedDialog(newTab: Boolean) {
            tab.blockedPopups++
            runOnUiThread {
                vb.tvBlockedPopupCounter.visibility = if (tab.blockedPopups > 0) View.VISIBLE else View.GONE
                vb.tvBlockedPopupCounter.text = tab.blockedPopups.toString()
                val msg = getString(if (newTab) R.string.new_tab_blocked else R.string.popup_dialog_blocked)
                NotificationView.showBottomRight(vb.rlRoot, R.drawable.ic_block_popups, msg)
            }
        }

        override fun onCreateWindow(dialog: Boolean, userGesture: Boolean): View? {
            val shouldBlockNewWindow = runBlocking(Dispatchers.Main.immediate) { tab.shouldBlockNewWindow(dialog, userGesture) }
            if (shouldBlockNewWindow) {
                onBlockedDialog(!dialog)
                return null
            }
            val tab = WebTabState(incognito = config.incognitoMode)
            val webView = createWebView(tab) ?: return null
            val currentTab = this@MainActivity.tabsModel.currentTab.value ?: return null
            val index = tabsModel.tabsStates.indexOf(currentTab) + 1
            tabsModel.tabsStates.add(index, tab)
            changeTab(tab)
            return webView
        }

        override fun closeWindow(internalRepresentation: Any) {
            if (config.isWebEngineGecko()) {
                for (tab in tabsModel.tabsStates) {
                    if ((tab.webEngine as GeckoWebEngine).session == internalRepresentation) {
                        closeTab(tab)
                        break
                    }
                }
            } else {
                for (tab in tabsModel.tabsStates) {
                    if (tab.webEngine.getView() == internalRepresentation) {
                        closeTab(tab)
                        break
                    }
                }
            }
        }

        override fun onScaleChanged(oldScale: Float, newScale: Float) {
            Log.d(TAG, "onScaleChanged: oldScale: $oldScale newScale: $newScale")
            val tabScale = tab.scale
            if (tab.changingScale) {
                tab.changingScale = false
                tab.scale = newScale
            } else if (tabScale != null && tabScale != newScale) {
                val zoomBy = tabScale / newScale
                Log.d(TAG, "Auto zoom by: $zoomBy")
                tab.changingScale = true
                tab.webEngine.zoomBy(zoomBy)
            }
        }

        override fun onCopyTextToClipboardRequested(url: String) {
            val clipBoard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clipData = ClipData.newPlainText("URL", url)
            clipBoard.setPrimaryClip(clipData)
            Toast.makeText(this@MainActivity, getString(R.string.copied_to_clipboard), Toast.LENGTH_SHORT).show()
        }

        override fun onShareUrlRequested(url: String) {
            val share = Intent(Intent.ACTION_SEND)
            share.type = "text/plain"
            share.putExtra(Intent.EXTRA_SUBJECT, R.string.share_url)
            share.putExtra(Intent.EXTRA_TEXT, url)
            try {
                startActivity(share)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
            }
        }

        override fun onOpenInExternalAppRequested(url: String) {
            val intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            intent.addCategory(Intent.CATEGORY_BROWSABLE)
            val activityComponent = intent.resolveActivity(this@MainActivity.packageManager)
            if (activityComponent != null && activityComponent.packageName == this@MainActivity.packageName) {
                Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
                return
            }
            try {
                startActivity(intent)
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
            }
        }

        override fun initiateVoiceSearch() {
            this@MainActivity.initiateVoiceSearch()
        }

        override fun onEditHomePageBookmarkSelected(index: Int) {
            lifecycleScope.launch {
                val bookmark = viewModel.homePageLinks.firstOrNull { it.order == index }
                var favoriteItem: FavoriteItem? = null
                if (bookmark?.favoriteId != null) {
                    favoriteItem = AppDatabase.db.favoritesDao().getById(bookmark.favoriteId)
                }
                if (favoriteItem == null) {
                    favoriteItem = FavoriteItem()
                    favoriteItem.title = bookmark?.title
                    favoriteItem.url = bookmark?.url
                    favoriteItem.order = index
                    favoriteItem.homePageBookmark = true
                    onEditHomePageBookmark(favoriteItem)
                } else {
                    AlertDialog.Builder(this@MainActivity)
                        .setTitle(R.string.bookmarks)
                        .setItems(arrayOf(getString(R.string.edit), getString(R.string.delete))) { _, which ->
                            when (which) {
                                0 -> onEditHomePageBookmark(favoriteItem)
                                1 -> viewModel.removeHomePageLink(bookmark!!)
                            }
                        }
                        .show()
                }
            }
        }

        override fun getHomePageLinks(): List<HomePageLink> {
            return viewModel.homePageLinks
        }

        override fun onPrepareForFullscreen() {
            //window.setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
            //        WindowManager.LayoutParams.FLAG_FULLSCREEN)
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    or View.SYSTEM_UI_FLAG_FULLSCREEN
                    or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
            isFullscreen = true
        }

        override fun onExitFullscreen() {
            //window.setFlags(0, WindowManager.LayoutParams.FLAG_FULLSCREEN)
            if (!config.keepScreenOn) {
                window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            window.decorView.systemUiVisibility =
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            isFullscreen = false
        }

        override fun onVisited(url: String) {
            val tab = tabsModel.currentTab.value ?: return

            if (!config.incognitoMode) {
                viewModel.logVisitedHistory(tab.title, url, tab.faviconHash)
            }
        }

        override fun suggestActionsForLink(href: String, x: Int, y: Int) {
            var s = href
            if (s.startsWith("\"") && s.endsWith("\"")) {
                s = s.substring(1, s.length - 1)
            }
            val url = s
            if (url.startsWith("http://", true) || url.startsWith("https://", true)) {
                val anchor = View(this@MainActivity)
                val lp = FrameLayout.LayoutParams(1, 1)
                lp.setMargins(x, y, 0, 0)
                vb.flWebViewContainer.addView(anchor, lp)
                linkActionsMenu = PopupMenu(this@MainActivity, anchor, Gravity.BOTTOM).also {
                    it.inflate(R.menu.menu_link)
                    it.setOnMenuItemClickListener { menuItem ->
                        when (menuItem.itemId) {
                            R.id.miOpenInNewTab -> onOpenInNewTabRequested(url, true)
                            R.id.miOpenInExternalApp -> onOpenInExternalAppRequested(url)
                            R.id.miDownload -> onDownloadRequested(url)
                            R.id.miCopyToClipboard -> onCopyTextToClipboardRequested(url)
                            R.id.miShare -> onShareUrlRequested(url)
                        }
                        true
                    }

                    it.setOnDismissListener {
                        vb.flWebViewContainer.removeView(anchor)
                        linkActionsMenu = null
                    }
                    it.show()
                }
            }
        }

        override fun markBookmarkRecommendationAsUseful(bookmarkOrder: Int) {
            viewModel.markBookmarkRecommendationAsUseful(bookmarkOrder)
        }
    }

    private fun askUserAndOpenInExternalApp(url: String, intent: Intent) {
        if (openUrlInExternalAppDialog != null) {
            return
        }
        openUrlInExternalAppDialog = AlertDialog.Builder(this)
            .setTitle(R.string.site_asks_to_open_unknown_url)
            .setMessage(getString(R.string.site_asks_to_open_unknown_url_message) + "\n\n" + url)
            .setPositiveButton(R.string.yes) { _, _ ->
                try {
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(this@MainActivity, R.string.external_app_open_error, Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton(R.string.no, null)
            .setOnDismissListener {
                openUrlInExternalAppDialog = null
            }
            .show()
    }

    private val downloadServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as? DownloadService.Binder
            if (binder == null) {
                Log.e(TAG, "Download service connection failed")
                //probably service still in another process due to incognito mode process switch
                //so we will try to reconnect in a few seconds
                uiHandler.postDelayed({
                    bindService(Intent(this@MainActivity, DownloadService::class.java),
                        this, Context.BIND_AUTO_CREATE)
                }, 1000)
                return
            }
            downloadService = binder.service
        }

        override fun onServiceDisconnected(p0: ComponentName?) {
            downloadService = null
        }
    }
}
