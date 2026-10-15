#pragma once

#include <cstddef>
#include <cstdint>
#include <memory>

#include <windows.h>

// Capture core. Knows nothing about Minecraft, Java or any renderer.
// Pipeline: WGC frame pool -> GPU copy to staging texture -> Map -> RGBA8 CPU buffer.
// Everything here is single-threaded: call from one thread (the Java render thread).
namespace wc {

// Creates the shared D3D11 device and the WinRT apartment. Idempotent.
bool Initialize();

// Drops the shared device. Close all sessions first.
void Shutdown();

class CaptureSession {
public:
	explicit CaptureSession(HWND target);
	~CaptureSession();

	CaptureSession(const CaptureSession &) = delete;
	CaptureSession &operator=(const CaptureSession &) = delete;

	// Creates the GraphicsCaptureItem, frame pool and session, then starts capture.
	bool Start();

	// Drains the frame pool, keeps the newest frame and copies it to the CPU.
	// Returns true when the CPU buffer has been refreshed with a new frame.
	// The CPU copy is one frame behind the GPU copy, so it never stalls on Map.
	bool Poll();

	uint32_t Width() const;
	uint32_t Height() const;

	// Copies the last frame as RGBA8 (bytes R,G,B,A per pixel) into dst.
	// Returns bytes written, or 0 if there is no frame or capacity is too small.
	size_t CopyRgba(uint8_t *dst, size_t capacity) const;

	HWND Window() const;

private:
	struct Impl;
	std::unique_ptr<Impl> m_impl;
};

}  // namespace wc
