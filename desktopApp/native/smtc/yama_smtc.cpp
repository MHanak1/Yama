// yama_smtc.cpp — Windows System Media Transport Controls (SMTC) shim for Yama.
//
// SMTC is a WinRT API; JNA (the JVM's C-ABI bridge, used for libvlc too) can't call it directly, and
// JDK 17 rules out Java's Panama/FFM. So this shim exposes a *flat C ABI* over WinRT that JNA maps
// trivially. See SMTC_PLAN.md for the full rationale. The Kotlin side is platform/SmtcService.kt.
//
// Threading: all WinRT work runs on one dedicated worker thread (MTA), so we never depend on the COM
// apartment of whichever JVM/AWT thread happens to call the C ABI. Callers enqueue tasks; the worker
// drains them. The ButtonPressed event is delivered by WinRT on its own threadpool thread and forwards
// straight to the registered callback — JNA attaches that native thread to the JVM automatically.
//
// The associated window's message pump (AWT's) must be running for media keys to reach SMTC; that's
// already the case for the Compose window whose HWND we bind to, so we need no pump of our own.
//
// Build (see .github/workflows/release.yml): cl /LD /std:c++17 /EHsc ... /link RuntimeObject.lib

#include <windows.h>
#include <systemmediatransportcontrolsinterop.h>
#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Media.h>
#include <winrt/Windows.Storage.Streams.h>

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <deque>
#include <functional>
#include <mutex>
#include <string>
#include <thread>

using namespace winrt;
using namespace winrt::Windows::Foundation;
using namespace winrt::Windows::Media;
using namespace winrt::Windows::Storage::Streams;

#define SMTC_API extern "C" __declspec(dllexport)

// button codes shared with the Kotlin side: 0 play, 1 pause, 2 next, 3 prev, 4 stop
typedef void (*smtc_button_cb)(int button);

namespace {

std::thread g_worker;
std::mutex g_mutex;                              // guards g_tasks
std::condition_variable g_cv;
std::deque<std::function<void()>> g_tasks;
std::atomic<bool> g_running{false};
std::atomic<smtc_button_cb> g_callback{nullptr};

// Owned by the worker thread once initialised.
SystemMediaTransportControls g_smtc{nullptr};
event_token g_buttonToken{};

// Ready handshake so smtc_init can report GetForWindow success synchronously.
std::mutex g_readyMutex;
std::condition_variable g_readyCv;
bool g_ready = false;
bool g_initOk = false;

void post(std::function<void()> task) {
    {
        std::lock_guard<std::mutex> lock(g_mutex);
        g_tasks.push_back(std::move(task));
    }
    g_cv.notify_one();
}

// Kotlin ABI status ints -> WinRT MediaPlaybackStatus (0 Stopped, 1 Paused, 2 Playing, else Closed).
MediaPlaybackStatus toStatus(int s) {
    switch (s) {
        case 0:  return MediaPlaybackStatus::Stopped;
        case 1:  return MediaPlaybackStatus::Paused;
        case 2:  return MediaPlaybackStatus::Playing;
        default: return MediaPlaybackStatus::Closed;
    }
}

void fireButton(int button) {
    if (auto cb = g_callback.load()) cb(button);
}

void workerMain(HWND hwnd) {
    init_apartment(apartment_type::multi_threaded);
    bool ok = false;
    try {
        // A Win32 (non-UWP) app gets its SMTC through this interop shim rather than the usual
        // GetForCurrentView(), which requires a CoreWindow we don't have.
        auto interop = get_activation_factory<SystemMediaTransportControls,
                                              ISystemMediaTransportControlsInterop>();
        check_hresult(interop->GetForWindow(
            hwnd, guid_of<SystemMediaTransportControls>(), put_abi(g_smtc)));

        g_buttonToken = g_smtc.ButtonPressed(
            [](SystemMediaTransportControls const&,
               SystemMediaTransportControlsButtonPressedEventArgs const& args) {
                switch (args.Button()) {
                    case SystemMediaTransportControlsButton::Play:     fireButton(0); break;
                    case SystemMediaTransportControlsButton::Pause:    fireButton(1); break;
                    case SystemMediaTransportControlsButton::Next:     fireButton(2); break;
                    case SystemMediaTransportControlsButton::Previous: fireButton(3); break;
                    case SystemMediaTransportControlsButton::Stop:     fireButton(4); break;
                    default: break;
                }
            });
        g_smtc.IsEnabled(true);
        ok = true;
    } catch (...) {
        ok = false;
    }

    {
        std::lock_guard<std::mutex> lock(g_readyMutex);
        g_ready = true;
        g_initOk = ok;
    }
    g_readyCv.notify_one();
    if (!ok) {
        uninit_apartment();
        return;
    }

    // Drain enqueued setter tasks until shutdown.
    while (true) {
        std::function<void()> task;
        {
            std::unique_lock<std::mutex> lock(g_mutex);
            g_cv.wait(lock, [] { return !g_tasks.empty() || !g_running.load(); });
            if (!g_running.load() && g_tasks.empty()) break;
            task = std::move(g_tasks.front());
            g_tasks.pop_front();
        }
        try { task(); } catch (...) {}
    }

    try {
        if (g_smtc) {
            g_smtc.ButtonPressed(g_buttonToken);
            g_smtc.IsEnabled(false);
            g_smtc = nullptr;
        }
    } catch (...) {}
    uninit_apartment();
}

} // namespace

// Spawns the worker and blocks until GetForWindow resolves. Returns 0 on success, -1 on failure.
SMTC_API int smtc_init(void* hwnd) {
    if (g_running.exchange(true)) return 0;  // already running
    {
        std::lock_guard<std::mutex> lock(g_readyMutex);
        g_ready = false;
        g_initOk = false;
    }
    g_worker = std::thread(workerMain, static_cast<HWND>(hwnd));

    std::unique_lock<std::mutex> lock(g_readyMutex);
    g_readyCv.wait(lock, [] { return g_ready; });
    if (!g_initOk) {
        g_running.store(false);
        g_cv.notify_one();
        if (g_worker.joinable()) g_worker.join();
        return -1;
    }
    return 0;
}

SMTC_API void smtc_set_button_callback(smtc_button_cb cb) {
    g_callback.store(cb);
}

SMTC_API void smtc_shutdown(void) {
    if (!g_running.exchange(false)) return;
    g_cv.notify_one();
    if (g_worker.joinable()) g_worker.join();
    g_callback.store(nullptr);
}

SMTC_API void smtc_set_enabled(int enabled) {
    post([enabled] { if (g_smtc) g_smtc.IsEnabled(enabled != 0); });
}

SMTC_API void smtc_set_playback_status(int status) {
    post([status] { if (g_smtc) g_smtc.PlaybackStatus(toStatus(status)); });
}

SMTC_API void smtc_set_buttons(int play, int pause, int next, int prev, int stop) {
    post([=] {
        if (!g_smtc) return;
        g_smtc.IsPlayEnabled(play != 0);
        g_smtc.IsPauseEnabled(pause != 0);
        g_smtc.IsNextEnabled(next != 0);
        g_smtc.IsPreviousEnabled(prev != 0);
        g_smtc.IsStopEnabled(stop != 0);
    });
}

SMTC_API void smtc_set_metadata(const wchar_t* title, const wchar_t* artist,
                                const wchar_t* album, const wchar_t* artUrl) {
    // Copy out of the caller's buffers immediately; the task runs later on the worker thread.
    std::wstring t   = title   ? title   : L"";
    std::wstring a   = artist  ? artist  : L"";
    std::wstring al  = album   ? album   : L"";
    std::wstring art = artUrl  ? artUrl  : L"";
    post([t, a, al, art] {
        if (!g_smtc) return;
        auto du = g_smtc.DisplayUpdater();
        du.Type(MediaPlaybackType::Music);
        auto mp = du.MusicProperties();
        mp.Title(hstring(t));
        mp.Artist(hstring(a));
        mp.AlbumTitle(hstring(al));
        if (!art.empty()) {
            // CreateFromUri handles both http(s) art (Jellyfin/Subsonic) and file: URLs.
            try { du.Thumbnail(RandomAccessStreamReference::CreateFromUri(Uri(hstring(art)))); }
            catch (...) { du.Thumbnail(nullptr); }
        } else {
            du.Thumbnail(nullptr);
        }
        du.Update();
    });
}

SMTC_API void smtc_set_timeline(long long positionMs, long long durationMs) {
    post([positionMs, durationMs] {
        if (!g_smtc) return;
        SystemMediaTransportControlsTimelineProperties tl;
        tl.StartTime(TimeSpan{0});
        tl.MinSeekTime(TimeSpan{0});
        tl.Position(std::chrono::milliseconds(positionMs));
        tl.MaxSeekTime(std::chrono::milliseconds(durationMs));
        tl.EndTime(std::chrono::milliseconds(durationMs));
        g_smtc.UpdateTimelineProperties(tl);
    });
}
