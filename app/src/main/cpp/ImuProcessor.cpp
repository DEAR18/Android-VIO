#include "ImuProcessor.h"

#include <android/log.h> // For logging in C++
#include <chrono>
#include <thread>

// Logging macro
#define LOG_TAG "ImuProcessor_JNI"
#define LOGD(...) __android_log_print(ANDROID_LOG_DEBUG, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

ImuProcessor::ImuProcessor() : running(false), javaCallbackObj(nullptr) {
  LOGD("ImuProcessor constructed.");
}

ImuProcessor::~ImuProcessor() {
  stopProcessing();
  if (javaVm && javaCallbackObj) {
    JNIEnv *env;
    if (javaVm->GetEnv((void **)&env, JNI_VERSION_1_6) == JNI_OK) {
      env->DeleteGlobalRef(javaCallbackObj);
      LOGD("Java callback object global reference deleted.");
    } else {
      LOGE("Failed to get JNIEnv for global reference deletion.");
    }
  }
  LOGD("ImuProcessor destructed.");
}

void ImuProcessor::startProcessing() {
  if (!running) {
    running = true;
    processingThread = std::thread(&ImuProcessor::processImuData, this);
    LOGD("Processing thread started.");
  }
}

void ImuProcessor::stopProcessing() {
  if (running) {
    running = false;
    dataCondition.notify_all();
    if (processingThread.joinable()) {
      processingThread.join();
      LOGD("Processing thread stopped and joined.");
    }
  }
}

void ImuProcessor::receiveImuData(long timestamp, float accX, float accY,
                                  float accZ, float gyroX, float gyroY,
                                  float gyroZ) {
  std::lock_guard<std::mutex> lock(queueMutex);
  inputQueue.push({timestamp, accX, accY, accZ, gyroX, gyroY, gyroZ});
  dataCondition.notify_one();
  LOGD("Received IMU data: accX=%.2f, gyroX=%.2f, ts=%ld", accX, gyroX,
       timestamp);
}

void ImuProcessor::setOutputCallback(JNIEnv *env, jobject callback_obj) {
  env->GetJavaVM(&javaVm);

  if (javaCallbackObj) {
    env->DeleteGlobalRef(javaCallbackObj);
  }
  javaCallbackObj = env->NewGlobalRef(callback_obj);
  if (!javaCallbackObj) {
    LOGE("Failed to create global reference for Java callback object!");
    return;
  }

  findJavaCallbackMethod(env);
  LOGD("Output callback set and global reference created.");
}

void ImuProcessor::findJavaCallbackMethod(JNIEnv *env) {
  if (javaCallbackObj == nullptr) {
    LOGE("javaCallbackObj is null, cannot find method!");
    return;
  }
  jclass callbackClass = env->GetObjectClass(javaCallbackObj);
  if (!callbackClass) {
    LOGE("Failed to get class of callback object!");
    return;
  }
  onProcessedImuDataMethodId =
      env->GetMethodID(callbackClass, "onProcessedImuData", "(JFFF)V");
  if (!onProcessedImuDataMethodId) {
    LOGE("Failed to find method ID for onProcessedImuData!");
  } else {
    LOGD("Found onProcessedImuData method ID.");
  }
  env->DeleteLocalRef(callbackClass);
}

void ImuProcessor::processImuData() {
  while (running) {
    ImuData currentImuData;
    {
      std::unique_lock<std::mutex> lock(queueMutex);
      dataCondition.wait(lock,
                         [this] { return !inputQueue.empty() || !running; });

      if (!running) {
        LOGD("Processing thread received stop signal. Exiting.");
        break;
      }

      currentImuData = inputQueue.front();
      inputQueue.pop();
    }

    LOGD("Processing IMU data for timestamp: %ld", currentImuData.timestamp);

    ProcessedImuData processedData;
    processedData.timestamp = currentImuData.timestamp;
    processedData.processedX = currentImuData.accX;
    processedData.processedY = currentImuData.accY;
    processedData.processedZ = currentImuData.accZ;

    if (javaVm && javaCallbackObj && onProcessedImuDataMethodId) {
      JNIEnv *env;
      jint attachStatus = javaVm->AttachCurrentThread(&env, NULL);
      if (attachStatus == JNI_ERR) {
        LOGE("Failed to attach current thread to JVM!");
        continue;
      }

      env->CallVoidMethod(javaCallbackObj, onProcessedImuDataMethodId,
                          processedData.timestamp, processedData.processedX,
                          processedData.processedY, processedData.processedZ);

      if (env->ExceptionCheck()) {
        env->ExceptionDescribe();
        env->ExceptionClear();
        LOGE("Exception occurred during Java callback!");
      }

      javaVm->DetachCurrentThread();
    } else {
      LOGE("Cannot callback to Java: callback object or method ID is null.");
    }
  }
}
