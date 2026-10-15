#include "input.h"

#include <dwmapi.h>

#include <cwctype>
#include <string>

namespace wc::input {
namespace {

struct SearchState {
	std::wstring needle;
	HWND found = nullptr;
};

std::wstring Lower(std::wstring text) {
	for (auto &ch : text) {
		ch = static_cast<wchar_t>(std::towlower(ch));
	}
	return text;
}

BOOL CALLBACK EnumProc(HWND hwnd, LPARAM param) {
	auto *state = reinterpret_cast<SearchState *>(param);

	if (!IsWindowVisible(hwnd)) {
		return TRUE;
	}

	const int length = GetWindowTextLengthW(hwnd);
	if (length <= 0) {
		return TRUE;
	}

	// GetWindowTextW writes length + NUL, so the buffer needs one extra slot.
	std::wstring title(static_cast<size_t>(length) + 1, L'\0');
	GetWindowTextW(hwnd, title.data(), length + 1);
	title.resize(static_cast<size_t>(length));

	if (Lower(title).find(state->needle) != std::wstring::npos) {
		state->found = hwnd;
		return FALSE;  // stop enumerating
	}
	return TRUE;
}

}  // namespace

HWND FindWindowByTitle(const wchar_t *substring) {
	if (!substring || !*substring) {
		return nullptr;
	}

	SearchState state{Lower(substring), nullptr};
	EnumWindows(EnumProc, reinterpret_cast<LPARAM>(&state));
	return state.found;
}

bool ClickFrame(HWND window, int32_t x, int32_t y) {
	if (!window || !IsWindow(window)) {
		return false;
	}

	// Frame origin: the extended frame bounds match what WGC captures (no invisible shadow).
	RECT bounds{};
	if (FAILED(DwmGetWindowAttribute(window, DWMWA_EXTENDED_FRAME_BOUNDS, &bounds, sizeof(bounds)))) {
		return false;
	}

	POINT point{bounds.left + x, bounds.top + y};

	// Focus can be refused by Windows' foreground rules. The click still goes
	// to whatever is under the cursor, so we do not fail on this.
	SetForegroundWindow(window);

	if (!SetCursorPos(point.x, point.y)) {
		return false;
	}

	INPUT inputs[2]{};
	inputs[0].type = INPUT_MOUSE;
	inputs[0].mi.dwFlags = MOUSEEVENTF_LEFTDOWN;
	inputs[1].type = INPUT_MOUSE;
	inputs[1].mi.dwFlags = MOUSEEVENTF_LEFTUP;

	return SendInput(2, inputs, sizeof(INPUT)) == 2;
}

}  // namespace wc::input
