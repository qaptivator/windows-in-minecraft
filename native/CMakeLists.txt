cmake_minimum_required(VERSION 3.21)
project(windowscraft_native LANGUAGES CXX)

if(NOT MSVC)
  message(FATAL_ERROR "windowscraft_native must be built with MSVC (Visual Studio generator).")
endif()

if(NOT CMAKE_SIZEOF_VOID_P EQUAL 8)
  message(FATAL_ERROR "Configure with -A x64. The Minecraft JVM is 64-bit.")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_CXX_EXTENSIONS OFF)

add_library(windowscraft_native SHARED
  src/capture.cpp
  src/input.cpp
  src/exports.cpp
)

target_compile_definitions(windowscraft_native PRIVATE
  UNICODE
  _UNICODE
  WIN32_LEAN_AND_MEAN
  NOMINMAX
)

target_compile_options(windowscraft_native PRIVATE
  /utf-8
  /permissive-
  /W3
  /EHsc
)

target_link_libraries(windowscraft_native PRIVATE
  d3d11        # D3D11CreateDevice, D3D11 interop
  dxgi         # IDXGIDevice
  windowsapp   # WinRT activation (Windows.Graphics.Capture)
  dwmapi       # DwmGetWindowAttribute (window frame bounds)
  user32       # windows, input
)
