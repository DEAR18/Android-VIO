cmake_minimum_required(VERSION 3.22.1)

project("android_vio_native")

find_library(log-lib log)

add_library(
        android-vio-native
        SHARED
        ImuProcessor.cpp
        native-lib.cpp
)

target_link_libraries(
        android-vio-native
        ${log-lib}
)