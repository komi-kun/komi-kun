# SPDX-License-Identifier: GPL-3.0-or-later
# KUN -->
# Builds the vendored `hoshidicts` static library (git submodule) and links it into
# `hoshidicts_jni`, the shared library consumed by :core:dictionary (contract §1.4/§1.6).
cmake_minimum_required(VERSION 3.22.1)

project(hoshidicts_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Optional targets in the fork are guarded by CLI/BENCHMARKS (not `_TESTS`); keep
# them off so AGP never cross-compiles the Android CLI/benchmarks (contract §1.6/§7).
set(HOSHIDICTS_BUILD_CLI OFF CACHE BOOL "")
set(HOSHIDICTS_BUILD_BENCHMARKS OFF CACHE BOOL "")

add_subdirectory(hoshidicts)

add_library(hoshidicts_jni SHARED
    hoshidicts_jni.cpp
)

target_link_libraries(hoshidicts_jni PRIVATE
    hoshidicts
)

# Explicit 16 KB page alignment (contract §1.4).
target_link_options(hoshidicts_jni PRIVATE
    "-Wl,-z,max-page-size=16384"
)
# KUN <--
