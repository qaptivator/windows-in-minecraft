#include "capture.h"

#include <winrt/Windows.Foundation.h>
#include <winrt/Windows.Graphics.h>
#include <winrt/Windows.Graphics.Capture.h>
#include <winrt/Windows.Graphics.DirectX.h>
#include <winrt/Windows.Graphics.DirectX.Direct3D11.h>

#include <windows.graphics.capture.interop.h>

#include <d3d11.h>
#include <dxgi.h>

#include <cstring>
#include <vector>

// Exported by d3d11.dll. Wraps a DXGI device as a WinRT IDirect3DDevice.
extern "C" HRESULT __stdcall CreateDirect3D11DeviceFromDXGIDevice(IDXGIDevice *dxgiDevice, ::IInspectable **graphicsDevice);

// Not declared by the SDK's interop headers in every version, so declared here.
// Gets the underlying DXGI interface from a WinRT Direct3D surface.
MIDL_INTERFACE("A9B3D012-3DF2-4EE3-B8D1-8695F457D3C1")
IDirect3DDxgiInterfaceAccess : public IUnknown {
	virtual HRESULT STDMETHODCALLTYPE GetInterface(REFIID iid, void **object) = 0;
};

namespace wc {
namespace {

namespace capture = winrt::Windows::Graphics::Capture;
namespace directx = winrt::Windows::Graphics::DirectX;
namespace directx11 = winrt::Windows::Graphics::DirectX::Direct3D11;

constexpr auto kPixelFormat = directx::DirectXPixelFormat::B8G8R8A8UIntNormalized;
constexpr int kBufferCount = 2;

struct SharedDevice {
	winrt::com_ptr<ID3D11Device> d3d;
	winrt::com_ptr<ID3D11DeviceContext> context;
	directx11::IDirect3DDevice winrtDevice{nullptr};
};

SharedDevice g_device;
bool g_initialized = false;

// Pulls the DXGI interface (here ID3D11Texture2D) out of a WinRT surface.
template <typename T>
winrt::com_ptr<T> GetDxgiInterface(winrt::Windows::Foundation::IInspectable const &object) {
	auto access = object.as<IDirect3DDxgiInterfaceAccess>();
	winrt::com_ptr<T> result;
	winrt::check_hresult(access->GetInterface(winrt::guid_of<T>(), result.put_void()));
	return result;
}

}  // namespace

// ---------------------------------------------------------------------------
// Shared device
// ---------------------------------------------------------------------------

bool Initialize() {
	if (g_initialized) {
		return true;
	}

	try {
		// MTA is what the frame pool needs. S_FALSE (already MTA) is not an exception.
		winrt::init_apartment(winrt::apartment_type::multi_threaded);
	} catch (winrt::hresult_error const &) {
		// Thread already has a different apartment model. Continue and hope it is usable.
	}

	// BGRA support is required so D3D surfaces can be wrapped for WinRT.
	const D3D_FEATURE_LEVEL levels[] = {D3D_FEATURE_LEVEL_11_1, D3D_FEATURE_LEVEL_11_0};
	winrt::com_ptr<ID3D11Device> device;
	winrt::com_ptr<ID3D11DeviceContext> context;
	HRESULT hr = D3D11CreateDevice(
			nullptr,
			D3D_DRIVER_TYPE_HARDWARE,
			nullptr,
			D3D11_CREATE_DEVICE_BGRA_SUPPORT,
			levels,
			ARRAYSIZE(levels),
			D3D11_SDK_VERSION,
			device.put(),
			nullptr,
			context.put());
	if (FAILED(hr)) {
		return false;
	}

	auto dxgiDevice = device.as<IDXGIDevice>();
	::IInspectable *raw = nullptr;
	hr = CreateDirect3D11DeviceFromDXGIDevice(dxgiDevice.get(), &raw);
	if (FAILED(hr)) {
		return false;
	}

	auto inspectable = winrt::Windows::Foundation::IInspectable{raw, winrt::take_ownership_from_abi};

	g_device.d3d = device;
	g_device.context = context;
	g_device.winrtDevice = inspectable.as<directx11::IDirect3DDevice>();
	g_initialized = true;
	return true;
}

void Shutdown() {
	g_device = SharedDevice{};
	g_initialized = false;
}

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

struct CaptureSession::Impl {
	HWND hwnd = nullptr;
	capture::GraphicsCaptureItem item{nullptr};
	capture::Direct3D11CaptureFramePool pool{nullptr};
	capture::GraphicsCaptureSession session{nullptr};

	// Two staging textures: copy into one while the other is mapped by the CPU.
	winrt::com_ptr<ID3D11Texture2D> staging[2];
	int writeIndex = 0;
	int readIndex = 0;
	bool hasPending = false;  // staging[readIndex] holds a copied frame not mapped yet

	uint32_t width = 0;
	uint32_t height = 0;
	std::vector<uint8_t> rgba;  // CPU copy, RGBA8

	// (Re)creates staging textures and the CPU buffer for a new frame size.
	void ResetStaging(uint32_t newWidth, uint32_t newHeight) {
		width = newWidth;
		height = newHeight;
		hasPending = false;
		writeIndex = 0;
		readIndex = 0;

		D3D11_TEXTURE2D_DESC desc{};
		desc.Width = newWidth;
		desc.Height = newHeight;
		desc.MipLevels = 1;
		desc.ArraySize = 1;
		desc.Format = DXGI_FORMAT_B8G8R8A8_UNORM;  // matches kPixelFormat
		desc.SampleDesc.Count = 1;
		desc.Usage = D3D11_USAGE_STAGING;
		desc.CPUAccessFlags = D3D11_CPU_ACCESS_READ;

		for (auto &texture : staging) {
			winrt::check_hresult(g_device.d3d->CreateTexture2D(&desc, nullptr, texture.put()));
		}

		rgba.assign(static_cast<size_t>(newWidth) * newHeight * 4, 0);
	}
};

CaptureSession::CaptureSession(HWND target) : m_impl(std::make_unique<Impl>()) {
	m_impl->hwnd = target;
}

CaptureSession::~CaptureSession() {
	if (!m_impl) {
		return;
	}
	if (m_impl->session) {
		m_impl->session.Close();
	}
	if (m_impl->pool) {
		m_impl->pool.Close();
	}
}

bool CaptureSession::Start() {
	if (!g_initialized || !m_impl->hwnd) {
		return false;
	}

	try {
		auto interop = winrt::get_activation_factory<capture::GraphicsCaptureItem, IGraphicsCaptureItemInterop>();

		capture::GraphicsCaptureItem item{nullptr};
		winrt::check_hresult(interop->CreateForWindow(
				m_impl->hwnd,
				winrt::guid_of<ABI::Windows::Graphics::Capture::IGraphicsCaptureItem>(),
				reinterpret_cast<void **>(winrt::put_abi(item))));
		m_impl->item = item;

		const auto size = item.Size();
		if (size.Width <= 0 || size.Height <= 0) {
			return false;  // minimized or not yet laid out
		}

		// Free-threaded pool: we poll it ourselves, so no DispatcherQueue is needed.
		m_impl->pool = capture::Direct3D11CaptureFramePool::CreateFreeThreaded(
				g_device.winrtDevice, kPixelFormat, kBufferCount, size);
		m_impl->session = m_impl->pool.CreateCaptureSession(item);

		// Both calls can throw on older Windows builds. Failing them is not fatal.
		try {
			m_impl->session.IsBorderRequired(false);
		} catch (...) {
		}
		try {
			// The cursor is drawn later by Minecraft, not baked into the frame.
			m_impl->session.IsCursorCaptureEnabled(false);
		} catch (...) {
		}

		m_impl->session.StartCapture();
		return true;
	} catch (winrt::hresult_error const &) {
		return false;
	}
}

bool CaptureSession::Poll() {
	auto &m = *m_impl;
	if (!m.pool) {
		return false;
	}

	try {
		// Drain the pool and keep only the newest frame so the view never lags behind.
		capture::Direct3D11CaptureFrame latest{nullptr};
		for (;;) {
			auto next = m.pool.TryGetNextFrame();
			if (!next) {
				break;
			}
			if (latest) {
				latest.Close();
			}
			latest = next;
		}
		if (!latest) {
			return false;
		}

		const auto content = latest.ContentSize();
		if (content.Width <= 0 || content.Height <= 0) {
			latest.Close();
			return false;
		}

		const auto newWidth = static_cast<uint32_t>(content.Width);
		const auto newHeight = static_cast<uint32_t>(content.Height);
		if (newWidth != m.width || newHeight != m.height) {
			m.pool.Recreate(g_device.winrtDevice, kPixelFormat, kBufferCount, content);
			m.ResetStaging(newWidth, newHeight);
		}

		// GPU side: copy the frame into the staging texture for this frame.
		auto surface = latest.Surface();
		auto texture = GetDxgiInterface<ID3D11Texture2D>(surface);
		const D3D11_BOX box{0, 0, 0, m.width, m.height, 1};
		g_device.context->CopySubresourceRegion(
				m.staging[m.writeIndex].get(), 0, 0, 0, 0, texture.get(), 0, &box);
		latest.Close();

		// CPU side: map the staging texture copied on the previous call.
		bool produced = false;
		if (m.hasPending) {
			ID3D11Resource *resource = m.staging[m.readIndex].get();
			D3D11_MAPPED_SUBRESOURCE mapped{};
			if (SUCCEEDED(g_device.context->Map(resource, 0, D3D11_MAP_READ, 0, &mapped))) {
				const auto *src = static_cast<const uint8_t *>(mapped.pData);
				const size_t rowBytes = static_cast<size_t>(m.width) * 4;

				for (size_t y = 0; y < m.height; ++y) {
					const uint8_t *srcRow = src + y * mapped.RowPitch;
					uint8_t *dstRow = m.rgba.data() + y * rowBytes;
					// BGRA -> RGBA, opaque alpha.
					for (size_t x = 0; x < rowBytes; x += 4) {
						dstRow[x + 0] = srcRow[x + 2];
						dstRow[x + 1] = srcRow[x + 1];
						dstRow[x + 2] = srcRow[x + 0];
						dstRow[x + 3] = 255;
					}
				}

				g_device.context->Unmap(resource, 0);
				produced = true;
			}
		}

		m.readIndex = m.writeIndex;
		m.writeIndex ^= 1;
		m.hasPending = true;
		return produced;
	} catch (winrt::hresult_error const &) {
		return false;
	}
}

uint32_t CaptureSession::Width() const {
	return m_impl->width;
}

uint32_t CaptureSession::Height() const {
	return m_impl->height;
}

size_t CaptureSession::CopyRgba(uint8_t *dst, size_t capacity) const {
	const auto &m = *m_impl;
	const size_t bytes = static_cast<size_t>(m.width) * m.height * 4;
	if (!dst || m.rgba.empty() || capacity < bytes) {
		return 0;
	}
	std::memcpy(dst, m.rgba.data(), bytes);
	return bytes;
}

HWND CaptureSession::Window() const {
	return m_impl->hwnd;
}

}  // namespace wc
