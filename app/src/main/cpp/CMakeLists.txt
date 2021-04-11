cmake_minimum_required(VERSION 3.4.1)

set(libs "${CMAKE_SOURCE_DIR}/jniLibs")
set(EIGEN3_INCLUDE_DIRECTORY "/usr/include/eigen3/")
set(OPENCV_INCLUDE_DIRECTORY "/media/gchaozheng/codes/Libs/opencv/build-arm64-v8a/install/sdk/native/jni/include")

include_directories(${EIGEN3_INCLUDE_DIRECTORY})
include_directories(${OPENCV_INCLUDE_DIRECTORY})

message(${libs}/3rdparty/${ANDROID_ABI})
message(${libs}/opencv/${ANDROID_ABI})

link_directories(${libs}/3rdparty/${ANDROID_ABI}
                 ${libs}/opencv/${ANDROID_ABI})

add_library(camera-util
            SHARED
            camera_util.cpp)

find_library(log-lib
             log)

target_link_libraries( camera-util

                        # Links the target library to the log library
                        android
                        opencv_rgbd
                        opencv_imgcodecs
                        opencv_imgproc
                        opencv_highgui
                        opencv_core
                        opencv_calib3d
                        opencv_highgui
                        cpufeatures
                        tegra_hal
                        IlmImf
                        ittnotify
                        quirc
                        libjasper
                        libjpeg-turbo
                        libpng
                        libtiff
                        libwebp
                        z

                        # included in the NDK.
                        ${log-lib} )