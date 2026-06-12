package com.rayneo.visionclaw

import android.app.Application
import com.rayneo.visionclaw.ui.MainViewModel

/**
 * Unipanel v2 Phase 2 — Application subclass that owns the single
 * process-wide [MainViewModel] instance.
 *
 * Why: the upcoming GeminiVoiceService (Phase 3+) runs in this same
 * process but lives outside any Activity's lifecycle. The voice
 * pipeline writes to `viewModel.appendUserUtterance`,
 * `viewModel.appendLiveAssistantStreamChunk`, and observes
 * `viewModel.messages` — and the ChatCardBridge publisher in
 * MainActivity drives off the same `messages` StateFlow. The Service
 * and the Activity therefore MUST share one MainViewModel instance,
 * otherwise the chat-cards the user sees in the unipanel overlay won't
 * reflect what the Service heard from Gemini.
 *
 * Approach (option (a) from the planning doc):
 *   - This class lazily constructs `viewModel` from the application
 *     context the first time anyone asks.
 *   - MainActivity's `getDefaultViewModelProviderFactory()` override
 *     returns a factory that hands back THIS instance, so both
 *     `by viewModels()` (on MainActivity) and `by activityViewModels()`
 *     (on ChatPanelFragment / SettingsPanelFragment / WebPanelFragment)
 *     end up with the same object.
 *   - The Service grabs it via `(applicationContext as VisionClawApp).viewModel`.
 *
 * Lifecycle note: because the ViewModel is constructed outside a
 * `ViewModelProvider`, its `onCleared()` will never fire and
 * `viewModelScope` lives for the process lifetime. That's the
 * intended semantics — the voice pipeline must outlive any single
 * Activity instance — and matches AndroidViewModel's contract that
 * the Application context it holds is process-scoped anyway.
 *
 * Manifest entry: `android:name=".VisionClawApp"` in `<application>`.
 *
 * Future phases:
 *   - Phase 3: GeminiVoiceService binds and reads `viewModel` from here.
 *   - Phase 4: pipeline relocation publishes voice state into this same
 *     `viewModel`.
 *   - Phase 5: visionclaw MainActivity may be deleted entirely; this
 *     Application class then becomes the only durable host of the VM.
 */
class VisionClawApp : Application() {

    /**
     * Shared MainViewModel. Lazy so we don't pay the construction
     * cost (DB open, OAuth bootstrap, etc.) at app start — only the
     * first consumer (Activity or Service) triggers init.
     */
    val viewModel: MainViewModel by lazy {
        MainViewModel(this)
    }

    override fun onCreate() {
        super.onCreate()
        // HUD bell notifications persist across app restarts: restores
        // the list + unread badge + dedupe memory at process start. The
        // rebuild re-created NotificationCenter.init() but this call —
        // the only thing that makes persistence actually run — was lost.
        com.rayneo.visionclaw.core.notifications.NotificationCenter.init(this)
    }
}
