cmake_minimum_required(VERSION 3.22.1)
project(spatial_fill LANGUAGES C)

# 编译口径：仅 arm64-v8a；16KB 页对齐。

# ── 空间壁纸的底图填充（PatchMatch，C 移植）────────────────────────────────
# 纯 C、无第三方依赖；只 arm64-v8a；16KB 页对齐。
add_library(spatial_fill SHARED
    spatial_fill_jni.c
    patchmatch/pm_image.c
    patchmatch/pm_nnf.c
    patchmatch/pm_inpaint.c
    patchmatch/pm_par.c)

target_include_directories(spatial_fill PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})
target_compile_options(spatial_fill PRIVATE
    -O3 -ffp-contract=fast -funroll-loops
    -Wall -Wextra -Wno-unused-parameter
    -fvisibility=hidden)
target_link_options(spatial_fill PRIVATE -Wl,-z,max-page-size=16384)
target_link_libraries(spatial_fill PRIVATE log)
