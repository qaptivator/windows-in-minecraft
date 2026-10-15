#pragma once

#include <cstdint>

#include <windows.h>

// Window lookup and input injection. Coordinates are in captured-frame pixels,
// which is how Java sees the mirrored window.
namespace wc::input {

// First visible top-level window whose title contains substring (case-insensitive).
HWND FindWindowByTitle(const wchar_t *substring);

// Focuses the window and clicks at (x, y) relative to its frame. Left button only.
// Uses SendInput, so it goes to whatever is under the cursor after focusing.
bool ClickFrame(HWND window, int32_t x, int32_t y);

}  // namespace wc::input
