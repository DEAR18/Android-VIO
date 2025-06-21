#include "ImuProcessor.h"
#include <android/log.h>
#include <jni.h>
#include <string>

// Logging macro
#define LOG_TAG_NATIVE "NativeLib_JNI"
#define LOGD_N(...)                                                            \
  __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG_NATIVE, __VA_ARGS__)
#define LOGE_N(...)                                                            \
  __android_log_print(ANDROID_LOG_ERROR, LOG_TAG_NATIVE, __VA_ARGS__)

static ImuProcessor *imuProcessor = nullptr;

// Java_com_example_android_1vio_MainActivity_nativeInitProcessor
extern "C" JNIEXPORT void JNICALL
Java_com_example_android_1vio_MainActivity_nativeInitProcessor(
    JNIEnv *env, jobject /* this */) {
  if (imuProcessor == nullptr) {
    imuProcessor = new ImuProcessor();
    LOGD_N("ImuProcessor instance created.");
  } else {
    LOGD_N("ImuProcessor instance already exists.");
  }
}

// Java_com_example_android_1vio_MainActivity_nativeDestroyProcessor
extern "C" JNIEXPORT void JNICALL
Java_com_example_android_1vio_MainActivity_nativeDestroyProcessor(
    JNIEnv *env, jobject /* this */) {
  if (imuProcessor != nullptr) {
    delete imuProcessor;
    imuProcessor = nullptr;
    LOGD_N("ImuProcessor instance destroyed.");
  }
}

// Java_com_example_android_1vio_MainActivity_nativeStartProcessing
extern "C" JNIEXPORT void JNICALL
Java_com_example_android_1vio_MainActivity_nativeStartProcessing(
    JNIEnv *env, jobject /* this */) {
  if (imuProcessor != nullptr) {
    imuProcessor->startProcessing();
    LOGD_N("ImuProcessor processing started.");
  } else {
    LOGE_N("ImuProcessor is not initialized! Cannot start processing.");
  }
}

// Java_com_example_android_1vio_MainActivity_nativeStopProcessing
extern "C" JNIEXPORT void JNICALL
Java_com_example_android_1vio_MainActivity_nativeStopProcessing(
    JNIEnv *env, jobject /* this */) {
  if (imuProcessor != nullptr) {
    imuProcessor->stopProcessing();
    LOGD_N("ImuProcessor processing stopped.");
  } else {
    LOGE_N("ImuProcessor is not initialized! Cannot stop processing.");
  }
}

// Java_com_example_android_1vio_MainActivity_nativeReceiveImuData
extern "C" JNIEXPORT void JNICALL
Java_com_example_android_1vio_MainActivity_nativeReceiveImuData(
    JNIEnv *env, jobject /* this */, jlong timestamp, jfloat accX, jfloat accY,
    jfloat accZ, jfloat gyroX, jfloat gyroY, jfloat gyroZ) {
  if (imuProcessor != nullptr) {
    imuProcessor->receiveImuData(timestamp, accX, accY, accZ, gyroX, gyroY,
                                 gyroZ);
  } else {
    LOGE_N("ImuProcessor is not initialized! Cannot receive IMU data.");
  }
}

// Java_com_example_android_1vio_MainActivity_nativeSetOutputCallback
extern "C" JNIEXPORT void JNICALL
Java_com_example_android_1vio_MainActivity_nativeSetOutputCallback(
    JNIEnv *env, jobject /* this */, jobject callback_obj) {
  if (imuProcessor != nullptr) {
    imuProcessor->setOutputCallback(env, callback_obj);
    LOGD_N("Output callback set in ImuProcessor.");
  } else {
    LOGE_N("ImuProcessor is not initialized! Cannot set output callback.");
  }
}
