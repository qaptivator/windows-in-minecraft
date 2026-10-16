// C ABI exported to Java (FFM). Keep these signatures in sync with
// src/client/java/dev/windowscraft/client/capture/NativeCapture.java.

#include "capture.h"
#include "input.h"

#include <cstdint>

#define WC_EXPORT extern "C" __declspec(dllexport)

namespace {

wc::CaptureSession *AsSession(void *session) {
	return static_cast<wc::CaptureSession *>(session);
}

}  // namespace

// () -> int
WC_EXPORT int32_t wc_init() {
	return wc::Initialize() ? 1 : 0;
}

// () -> void
WC_EXPORT void wc_shutdown() {
	wc::Shutdown();
}

// (const wchar_t* substring) -> HWND or NULL
WC_EXPORT void *wc_find_window(const wchar_t *title_substring) {
	return wc::input::FindWindowByTitle(title_substring);
}

// (HWND) -> session or NULL
WC_EXPORT void *wc_open_window(void *hwnd) {
	auto *session = new wc::CaptureSession(static_cast<HWND>(hwnd));
	if (!session->Start()) {
		delete session;
		return nullptr;
	}
	return session;
}

// (session) -> void
WC_EXPORT void wc_close(void *session) {
	delete AsSession(session);
}

// (session) -> 1 if a new frame is ready, else 0
WC_EXPORT int32_t wc_poll(void *session) {
	if (!session) {
		return 0;
	}
	return AsSession(session)->Poll() ? 1 : 0;
}

// (session, int32* width, int32* height) -> 1 on success
WC_EXPORT int32_t wc_get_size(void *session, int32_t *width, int32_t *height) {
	if (!session || !width || !height) {
		return 0;
	}
	*width = static_cast<int32_t>(AsSession(session)->Width());
	*height = static_cast<int32_t>(AsSession(session)->Height());
	return 1;
}

// (session, uint8* dst, uint64 capacity, int32 dst_width, int32 dst_height) -> bytes written (0 on failure)
WC_EXPORT int64_t wc_copy_rgba_scaled(void *session, uint8_t *dst, uint64_t capacity, int32_t dst_width, int32_t dst_height) {
	if (!session || dst_width <= 0 || dst_height <= 0) {
		return 0;
	}
	return static_cast<int64_t>(AsSession(session)->CopyRgbaScaled(
			dst, static_cast<size_t>(capacity), static_cast<uint32_t>(dst_width), static_cast<uint32_t>(dst_height)));
}

// (HWND, int32 x, int32 y) -> 1 on success
WC_EXPORT int32_t wc_click(void *hwnd, int32_t x, int32_t y) {
	return wc::input::ClickFrame(static_cast<HWND>(hwnd), x, y) ? 1 : 0;
}
